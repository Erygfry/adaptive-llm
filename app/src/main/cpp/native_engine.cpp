#include <android/log.h>
#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>
#include <cmath>
#include <cstdio>
#include <chrono>
#include <vector>
#include <unistd.h>
#include <regex>
#include <sched.h>
#include <errno.h>

#include "chat.h"
#include "common.h"
#include "sampling.h"
#include "llama.h"
#include "ggml-backend.h"
#include "ngram-map.h"

#define TAG "AdaptiveLLM"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// --- Constants ---
static constexpr int BATCH_SIZE = 512;
// Fallback context size used when nativeLoadModel получает nCtx <= 0 (старые
// callers без RAM tier mapping'а). Реально используемый размер хранится в
// g_n_ctx ниже — устанавливается при loadModel из Kotlin-стороны по
// deviceProfile.ramTier (6144 / 8192 / 16384 для MID / UPPER_MID / HIGH).
static constexpr int DEFAULT_CONTEXT_SIZE = 8192;
static constexpr int OVERFLOW_HEADROOM = 4;
static constexpr int N_THREADS_MIN = 2;
static constexpr int N_THREADS_MAX = 6;
static constexpr int N_THREADS_HEADROOM = 2;
static constexpr float DEFAULT_TEMP = 0.3f;
static constexpr int SOFT_LIMIT_THINKING = 1024;      // thinking mode — chain-of-thought easily eats 1000+ tokens
static constexpr int SOFT_LIMIT_NORMAL = 128;         // no thinking — shorter buffer is enough
static constexpr int RESET_THRESHOLD_THINKING = 1500; // ALWAYS/AUTO mode — нужен запас на reasoning
static constexpr int RESET_THRESHOLD_NORMAL = 500;    // NEVER mode — короткий ответ

// --- Global state ---
static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static llama_batch g_batch;
static common_chat_templates_ptr g_chat_templates;
static common_sampler *g_sampler = nullptr;
// Actual context size used for the loaded model — set in nativeLoadModel from the
// nCtx JNI parameter (which Kotlin computes from deviceProfile.ramTier). Все
// проверки переполнения контекста ниже используют g_n_ctx, а не DEFAULT_CONTEXT_SIZE.
static int g_n_ctx = DEFAULT_CONTEXT_SIZE;

// Cooperative cancellation flag for model loading. nativeLoadModel sets up a
// progress_callback that returns !g_cancel_load — when this flag is true, llama.cpp
// abort'ит загрузку и вернёт null model. Закрывает UX issue где пользователь во
// время load'а (5-10 сек) нажимал back и ждал завершения load'а перед transition'ом.
// Reset'ится в начале каждого nativeLoadModel.
static volatile bool g_cancel_load = false;

// Chat state
// =============================================================================
// TODO(memory-system, B.3): УДАЛИТЬ ПРИ ВВЕДЕНИИ nativeChatCompletion API
// -----------------------------------------------------------------------------
// g_chat_msgs не используется — только clear'ится в reset_chat_state, никогда
// не читается. Остаток от изначального дизайна где native слой держал свою
// копию chat history. Сейчас history фактически живёт в g_token_history
// (для prefix matching) и в Kotlin _messages (для UI).
//
// При chat completion API messages приходят с Kotlin стороны JSON массивом
// на каждый вызов, native слой stateless по chat history. g_chat_msgs можно
// будет удалить вместе с этим переходом.
// =============================================================================
static std::vector<common_chat_msg> g_chat_msgs;
static llama_pos g_system_pos = 0;
static llama_pos g_current_pos = 0;
static llama_pos g_stop_pos = 0;
// Snapshot of g_current_pos right after the user prompt was decoded, BEFORE generation
// started. Used by nativeCancelAndCleanKV to seq_rm partial tokens generated before
// user pressed cancel (so the next turn doesn't see garbage half-thoughts in KV).
static llama_pos g_pos_before_generation = 0;
static std::string g_cached_chars;
static std::ostringstream g_assistant_ss;
// 0 = AUTO (legacy, model decides), 1 = ALWAYS (force thinking), 2 = NEVER (disable thinking)
static int g_thinking_mode = 0;
static bool g_using_gpu = false;                    // true if model was loaded with GPU layers
// =============================================================================
// TODO(memory-system, B.4): УДАЛИТЬ ПОСЛЕ Phase 4 EVICTION
// -----------------------------------------------------------------------------
// Workaround на случай нехватки контекста под thinking — когда RESET_THRESHOLD_*
// не помещается в оставшийся space, мы автоматически выключаем thinking для
// текущего turn'а. UI потом (через wasThinkingDisabled() + setThinkingMode())
// восстанавливает пользовательский выбор.
//
// С Phase 4 eviction этот случай не должен происходить — eviction триггерится
// при превышении T_max, компрессирует историю в summary и освобождает место
// раньше чем кончатся токены под thinking.
//
// Удалять вместе с:
//   - g_thinking_disabled_by_context (этот глобал)
//   - nativeWasThinkingDisabled() JNI метод
//   - InferenceEngine.wasThinkingDisabled() в Kotlin интерфейсе
//   - ветки в MainViewModel.sendMessage parser которые проверяют thinkingActive
//     с учётом wasThinkingDisabled (lines 290, 339-342 текущей версии)
// =============================================================================
static bool g_thinking_disabled_by_context = false; // true when thinking was auto-disabled due to low context
// Speculative decoding state
static std::vector<llama_token> g_token_history; // all tokens (prompt + generated) for n-gram lookup
static std::vector<llama_token> g_spec_buffer;   // verified draft tokens waiting to be returned
static size_t g_spec_buffer_pos = 0;
static int g_next_logit_idx = -1;      // which logit index to sample from (-1 = last)
static constexpr int SPEC_N_GRAM = 4;  // n-gram size for pattern matching
static constexpr int SPEC_N_DRAFT = 8; // max draft tokens per speculation
static const common_ngram_simple_config g_ngram_config = {SPEC_N_GRAM, SPEC_N_DRAFT};
static bool g_batch_initialized = false;
static std::string g_system_prompt_text;

// Stage 5: текущая GBNF grammar для sampler'а. Пустая = unconstrained generation.
// При смене (set/clear) пересоздаём g_sampler через recreate_sampler() — нет
// stable API для замены grammar на live sampler'е, поэтому проще re-init.
static std::string g_current_grammar;

// --- Helpers ---

/**
 * Читает максимальную частоту каждого ядра из sysfs и возвращает
 * список "быстрых" ядер (big / prime).
 *
 * Логика: собираем уникальные частоты, сортируем. Берём ядра из
 * верхнего кластера (максимальная частота). На трёхкластерных SoC
 * (prime + big + LITTLE) также захватываем средний кластер,
 * если он >= 80% от prime.
 */
static std::vector<int> detect_big_cores()
{
    int n_cpus = (int)sysconf(_SC_NPROCESSORS_ONLN);
    LOGi("detect_big_cores: scanning %d CPUs", n_cpus);
    std::vector<std::pair<int, long>> cores; // (core_id, max_freq_khz)

    for (int i = 0; i < n_cpus; i++)
    {
        char path[128];
        snprintf(path, sizeof(path),
                 "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (!f)
        {
            LOGi("  cpu%d: cannot read cpufreq (permission denied?)", i);
            continue;
        }
        long freq = 0;
        if (fscanf(f, "%ld", &freq) == 1 && freq > 0)
        {
            cores.push_back({i, freq});
        }
        fclose(f);
        LOGi("  cpu%d: max_freq=%ld kHz", i, freq);
    }

    if (cores.empty())
        return {}; // sysfs не читается — fallback

    // Collect unique frequencies sorted descending
    std::vector<long> unique_freqs;
    for (auto &c : cores)
    {
        bool found = false;
        for (long f : unique_freqs)
            if (f == c.second)
            {
                found = true;
                break;
            }
        if (!found)
            unique_freqs.push_back(c.second);
    }
    std::sort(unique_freqs.begin(), unique_freqs.end(), std::greater<long>());

    LOGi("  found %zu frequency clusters:", unique_freqs.size());
    for (long f : unique_freqs)
        LOGi("    %ld kHz", f);

    if (unique_freqs.size() < 2)
        return {}; // all cores same frequency — no big/LITTLE split

    // Take the top cluster (fastest cores)
    long top_freq = unique_freqs[0];
    // On 3-cluster SoCs, also include mid cluster if >= 80% of top
    long threshold = top_freq;
    if (unique_freqs.size() >= 3 && unique_freqs[1] >= top_freq * 80 / 100)
    {
        threshold = unique_freqs[1];
        LOGi("  3-cluster SoC: including mid cluster at %ld kHz", threshold);
    }

    std::vector<int> big;
    for (auto &c : cores)
    {
        if (c.second >= threshold)
        {
            big.push_back(c.first);
        }
    }

    return big;
}

/**
 * Устанавливает CPU affinity mask для текущего потока.
 * Все потоки, которые llama.cpp создаст позже через std::thread,
 * наследуют эту маску — поэтому достаточно вызвать один раз
 * перед созданием контекста.
 */
static bool pin_threads_to_cores(const std::vector<int> &cores)
{
    if (cores.empty())
        return false;

    cpu_set_t mask;
    CPU_ZERO(&mask); // обнуляем маску
    for (int core : cores)
    {
        CPU_SET(core, &mask); // добавляем нужные ядра
    }

    // pid=0 означает "текущий поток"
    if (sched_setaffinity(0, sizeof(mask), &mask) != 0)
    {
        LOGw("sched_setaffinity failed: %s", strerror(errno));
        return false;
    }
    return true;
}

// Progress callback for llama_model_load_from_file. Called periodically during
// model loading (tensor loading, kv prep). Returning false aborts the load.
// We check g_cancel_load flag — Kotlin sets it via nativeCancelLoading when user
// navigates away mid-load.
static bool model_load_progress_callback(float progress, void * /*user_data*/)
{
    if (g_cancel_load) {
        LOGw("Model load aborted by user at %.1f%%", progress * 100.0f);
        return false;
    }
    return true;
}

// Check if the model supports context shifting.
// seq_add requires n_pos_per_embd == 1, which is NOT the case for models
// with multi-rope (MROPE/IMROPE) like Qwen3.5 — positions can't be linearly shifted.
static bool g_can_shift = false;

static void detect_shift_support()
{
    auto rope = llama_model_rope_type(g_model);
    g_can_shift = (rope != LLAMA_ROPE_TYPE_MROPE && rope != LLAMA_ROPE_TYPE_IMROPE);
    LOGi("Context shift support: %s (rope_type=%d)", g_can_shift ? "yes" : "no", (int)rope);
}

// Returns the soft limit headroom based on current thinking mode.
// AUTO (0) uses thinking headroom since the model may decide to think.
static int get_soft_limit()
{
    return (g_thinking_mode == 2) ? SOFT_LIMIT_NORMAL : SOFT_LIMIT_THINKING;
}

// Returns true if shift succeeded, false if not supported
static bool shift_context()
{
    if (!g_can_shift)
    {
        LOGw("shift_context: not supported for this model (multi-rope), ending generation");
        return false;
    }

    const int n_discard = (g_current_pos - g_system_pos) / 2;
    LOGi("shift_context: discarding %d tokens", n_discard);

    auto *mem = llama_get_memory(g_context);
    llama_memory_seq_rm(mem, 0, g_system_pos, g_system_pos + n_discard);
    llama_memory_seq_add(mem, 0, g_system_pos + n_discard, g_current_pos, -n_discard);
    g_current_pos -= n_discard;
    return true;
}

static std::string chat_add_and_format(const std::string &role, const std::string &content)
{
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    if (role != "user")
    {
        // Assistant/system: use legacy path, no generation prompt
        return common_chat_format_single(
            g_chat_templates.get(), {}, msg, false, false);
    }
    // User message: behaviour depends on thinking mode
    if (g_thinking_mode == 0)
    {
        // AUTO: legacy template, model decides whether to think
        return common_chat_format_single(
            g_chat_templates.get(), {}, msg, true, false);
    }
    // ALWAYS (1) or NEVER (2): use Jinja with enable_thinking flag
    common_chat_templates_inputs inputs;
    inputs.use_jinja = true;
    inputs.enable_thinking = (g_thinking_mode == 1);
    inputs.add_bos = false;
    inputs.messages = {msg};
    inputs.add_generation_prompt = true;
    return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
}

// Format a HISTORICAL message (for replay при chat switch) — без generation prompt
// в конце. Используется при загрузке существующего чата чтобы восстановить контекст
// в KV cache. Отличается от chat_add_and_format:
//   - всегда legacy template (без Jinja и thinking-mode injection'ов): мы replay'им
//     уже сгенерированные turn'ы, никакой thinking control не нужен
//   - add_ass=false: следующий turn будет добавлен явно, не через generation
//
// Assistant-сообщения хранятся в БД как clean text (thinking strip'ается на UI слое
// перед сохранением — см. MainViewModel parser). Template Qwen3.5 в любом случае
// auto-strips <think>...</think> из исторических assistant turn'ов.
static std::string chat_format_for_history(const std::string &role, const std::string &content)
{
    common_chat_msg msg;
    msg.role = role;
    msg.content = content;
    return common_chat_format_single(
        g_chat_templates.get(), {}, msg, false, false);
}

static int decode_in_batches(const llama_tokens &tokens, llama_pos start, bool logit_last = false)
{
    for (int i = 0; i < (int)tokens.size(); i += BATCH_SIZE)
    {
        const int n = std::min((int)tokens.size() - i, BATCH_SIZE);
        common_batch_clear(g_batch);

        if (start + i + n >= g_n_ctx - OVERFLOW_HEADROOM)
        {
            if (!shift_context())
                return 1; // context full, can't shift
        }

        for (int j = 0; j < n; j++)
        {
            bool want_logit = logit_last && (i + j == (int)tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], start + i + j, {0}, want_logit);
        }

        if (llama_decode(g_context, g_batch) != 0)
        {
            LOGe("llama_decode failed");
            return 1;
        }
    }
    return 0;
}

static bool is_valid_utf8(const char *s)
{
    if (!s)
        return true;
    const auto *b = (const unsigned char *)s;
    while (*b)
    {
        int n;
        if ((*b & 0x80) == 0x00)
            n = 1;
        else if ((*b & 0xE0) == 0xC0)
            n = 2;
        else if ((*b & 0xF0) == 0xE0)
            n = 3;
        else if ((*b & 0xF8) == 0xF0)
            n = 4;
        else
            return false;
        b++;
        for (int i = 1; i < n; i++)
        {
            if ((*b & 0xC0) != 0x80)
                return false;
            b++;
        }
    }
    return true;
}

/**
 * Пересоздаёт g_sampler с заданной grammar (пустая = unconstrained). Используется
 * для:
 *   - первичной инициализации в nativeLoadModel (grammar = "")
 *   - применения grammar через nativeSetGrammar (Stage 5+, для structured output
 *     в eviction LLM calls)
 *   - очистки grammar через nativeClearGrammar (возврат к свободной generation)
 *
 * common_sampler не имеет API для in-place замены grammar, поэтому полное re-init.
 * Стоимость — миллисекунды, происходит редко (при переходах между chat completion
 * и system LLM calls в Stage 6+ eviction).
 */
static void recreate_sampler(const std::string &grammar)
{
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    common_params_sampling sparams;
    sparams.temp = DEFAULT_TEMP;
    sparams.penalty_repeat = 1.1f;
    sparams.penalty_last_n = 64;
    if (!grammar.empty()) {
        // common_grammar — struct {type, grammar_string}. User-provided GBNF =
        // COMMON_GRAMMAR_TYPE_USER (vs OUTPUT_FORMAT для JSON schemas или
        // TOOL_CALLS для chat templates).
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    }
    g_sampler = common_sampler_init(g_model, sparams);
    g_current_grammar = grammar;
    LOGi("recreate_sampler: grammar_len=%zu", grammar.size());
}

static void reset_chat_state(bool clear_kv = true)
{
    g_chat_msgs.clear();
    g_system_pos = 0;
    g_current_pos = 0;
    g_stop_pos = 0;
    g_pos_before_generation = 0;
    g_cached_chars.clear();
    g_assistant_ss.str("");
    g_token_history.clear();
    g_spec_buffer.clear();
    g_spec_buffer_pos = 0;
    g_next_logit_idx = -1;
    if (clear_kv && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

// =====================================================================
// JNI exports
// =====================================================================

extern "C"
{

    // --- init: load backends and initialize llama ---
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeInit(
        JNIEnv *env, jobject, jstring jBackendPath)
    {
        llama_log_set([](enum ggml_log_level level, const char *text, void *)
                      {
        int prio = ANDROID_LOG_DEFAULT;
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
            case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
            case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
            default:                   prio = ANDROID_LOG_DEBUG;  break;
        }
        __android_log_print(prio, TAG, "%s", text); }, nullptr);

        const char *path = env->GetStringUTFChars(jBackendPath, nullptr);
        LOGi("Loading backends from: %s", path);
        ggml_backend_load_all_from_path(path);
        env->ReleaseStringUTFChars(jBackendPath, path);

        llama_backend_init();
        LOGi("Backend initialized");
    }

    // --- loadModel ---
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeLoadModel(
        JNIEnv *env, jobject, jstring jModelPath, jint nGpuLayers, jint nCtx)
    {
        const char *path = env->GetStringUTFChars(jModelPath, nullptr);
        // nCtx <= 0 trigger fallback на DEFAULT_CONTEXT_SIZE — на случай если
        // Kotlin сторона не передала валидный размер (старые callers / тестовый
        // путь). В production Kotlin всегда передаёт 6144/8192/16384.
        g_n_ctx = (nCtx > 0) ? (int)nCtx : DEFAULT_CONTEXT_SIZE;
        LOGi("Loading model: %s (n_gpu_layers=%d, n_ctx=%d)", path, (int)nGpuLayers, g_n_ctx);

        // Reset cancellation flag at start of load — иначе если предыдущая загрузка
        // была отменена, новая отменится сразу же.
        g_cancel_load = false;

        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = (int)nGpuLayers;
        params.progress_callback = model_load_progress_callback;
        params.progress_callback_user_data = nullptr;
        g_using_gpu = (int)nGpuLayers != 0;

        // When GPU is not requested, restrict to CPU-only devices
        // to avoid llama.cpp using a registered Vulkan backend
        // that may not support this device (e.g. Adreno 618)
        std::vector<ggml_backend_dev_t> cpu_devices;
        if ((int)nGpuLayers == 0)
        {
            for (size_t i = 0; i < ggml_backend_dev_count(); i++)
            {
                auto *dev = ggml_backend_dev_get(i);
                if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU ||
                    ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_ACCEL)
                {
                    cpu_devices.push_back(dev);
                    LOGi("CPU-only mode: using device '%s'", ggml_backend_dev_name(dev));
                }
            }
            cpu_devices.push_back(nullptr); // null-terminated
            params.devices = cpu_devices.data();
        }

        g_model = llama_model_load_from_file(path, params);
        env->ReleaseStringUTFChars(jModelPath, path);

        if (!g_model)
        {
            // Distinguish user-cancellation (code 99 — special-cased by Kotlin to
            // throw CancellationException, не Error) от actual load failures (code 1).
            if (g_cancel_load) {
                LOGi("Model load was cancelled by user");
                g_cancel_load = false; // reset for next attempt
                return 99;
            }
            LOGe("Failed to load model");
            return 1;
        }

        // Create context
        // Detect big cores and pin threads to them
        auto big_cores = detect_big_cores();
        int n_threads;
        static constexpr int MIN_BIG_CORES_FOR_PINNING = 4;

        if ((int)big_cores.size() >= MIN_BIG_CORES_FOR_PINNING)
        {
            // Enough big cores — pin to them only
            n_threads = (int)big_cores.size();
            if (pin_threads_to_cores(big_cores))
            {
                LOGi("Pinned %d threads to %zu big cores", n_threads, big_cores.size());
                for (int c : big_cores)
                    LOGi("  big core: cpu%d", c);
            }
            else
            {
                LOGw("Thread pinning failed, using %d threads without affinity", n_threads);
            }
        }
        else
        {
            // Too few big cores (or detection failed) — use all cores
            n_threads = std::max(N_THREADS_MIN,
                                 std::min(N_THREADS_MAX, (int)sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
            if (!big_cores.empty())
                LOGi("Only %zu big cores (need >= %d for pinning), using %d threads on all cores",
                     big_cores.size(), MIN_BIG_CORES_FOR_PINNING, n_threads);
            else
                LOGi("No big/LITTLE split detected, using %d threads", n_threads);
        }

        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = g_n_ctx;
        ctx_params.n_batch = BATCH_SIZE;
        ctx_params.n_ubatch = BATCH_SIZE;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;
        ctx_params.type_k = GGML_TYPE_Q4_0; // KV cache quantization (default F16)
        ctx_params.type_v = GGML_TYPE_Q4_0; // Qwen3.5 hybrid: only 8/30 layers use KV, likely lossless
        // TODO(memory-system): offload_kqv left as default (true). Tested false on
        // Pixel 9 Vulkan + Q4_0 KV — state_seq_save/load became deterministic (cosine=1.0)
        // BUT inference output became garbage (random tokens). Likely a llama.cpp bug
        // in the Q4_0+offload_kqv=false+Vulkan code path. Не использовать пока не
        // подтверждён upstream fix. Для memory architecture идём по Option D:
        // Vulkan-устройства skip kv_cache.bin persist, full re-decode на chat entry.

        g_context = llama_init_from_model(g_model, ctx_params);
        if (!g_context)
        {
            LOGe("Failed to create context");
            llama_model_free(g_model);
            g_model = nullptr;
            return 2;
        }

        g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
        g_batch_initialized = true;
        g_chat_templates = common_chat_templates_init(g_model, "");
        // Initial sampler без grammar — будет применена по nativeSetGrammar при
        // необходимости (Stage 6 eviction calls).
        recreate_sampler("");

        detect_shift_support();
        reset_chat_state(false);
        LOGi("Model loaded successfully");
        return 0;
    }

    // =========================================================================
    // TODO(memory-system, B.2): РАЗДЕЛИТЬ НА ДВА API ПРИ ИМПЛЕМЕНТАЦИИ Phase 5
    // -------------------------------------------------------------------------
    // Текущий setSystemPrompt делает: reset_chat_state() (wipe KV + clear globals)
    // → tokenize prompt → decode в KV → запомнить позицию system prompt. Это OK
    // для single-chat MVP, но конфликтует со snapshot_base flow:
    //
    //   - В архитектуре snapshot_base.bin создаётся ОДИН РАЗ при bootstrap
    //     (после первого setSystemPrompt-эквивалента) и хранится как файл
    //   - При создании каждого нового чата — `loadSnapshotBase()` восстанавливает
    //     KV в state `[system_only]` через llama_state_seq_load_file
    //   - Это в десятки раз быстрее чем fresh decode system prompt'а каждый раз
    //
    // План замены (см. architecture.md, Bootstrap «Snapshot_base инвалидация»):
    //   - nativeCreateSnapshotBaseIfNeeded(prompt, hash) — создаёт snapshot_base.bin
    //     если нет или невалиден (validates через system_prompt_hash)
    //   - nativeLoadSnapshotBase() — restore KV in [system_only] из файла
    //     (или re-decode на Vulkan backend'ах где state_seq_save_file сломан —
    //     см. Phase 5 «Backend compatibility»)
    //
    // Текущая функция останется как deprecated compatibility shim в начале
    // имплементации Phase 5, затем удалится.
    // =========================================================================
    // --- processSystemPrompt ---
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSetSystemPrompt(
        JNIEnv *env, jobject, jstring jPrompt)
    {
        // Защита от race condition'а: пользователь может быстро навигировать
        // (back-back-back) и triggernуть unloadModel ПОКА Kotlin сторона уже
        // запланировала setSystemPrompt на llamaDispatcher. После unload'а
        // g_model/g_context/g_chat_templates становятся null/reset, и
        // common_chat_templates_was_explicit крашится с SIGSEGV. Возвращаем
        // error code, Kotlin интерпретирует как load failure и graceful'но
        // откатывает UI state.
        if (!g_model || !g_context || !g_chat_templates) {
            LOGe("nativeSetSystemPrompt: model not loaded (race with unload?)");
            return 1;
        }

        reset_chat_state();

        const char *raw = env->GetStringUTFChars(jPrompt, nullptr);
        std::string prompt(raw);
        env->ReleaseStringUTFChars(jPrompt, raw);

        g_system_prompt_text = prompt;

        bool has_tmpl = common_chat_templates_was_explicit(g_chat_templates.get());

        // Format system prompt via legacy path (not Jinja) and do NOT add to g_chat_msgs.
        // System content is baked into KV cache; Jinja only processes user/assistant turns.
        std::string formatted;
        if (has_tmpl)
        {
            common_chat_msg msg;
            msg.role = "system";
            msg.content = prompt;
            formatted = common_chat_format_single(
                g_chat_templates.get(), {}, msg, false, false);
        }
        else
        {
            formatted = prompt;
        }

        auto tokens = common_tokenize(g_context, formatted, has_tmpl, has_tmpl);
        g_token_history.insert(g_token_history.end(), tokens.begin(), tokens.end());

        if ((int)tokens.size() > g_n_ctx - OVERFLOW_HEADROOM)
        {
            LOGe("System prompt too long: %d tokens", (int)tokens.size());
            return 1;
        }

        if (decode_in_batches(tokens, 0))
            return 2;

        g_system_pos = g_current_pos = (int)tokens.size();
        LOGi("System prompt processed: %d tokens", (int)tokens.size());
        return 0;
    }

    // --- processUserPrompt ---
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeProcessUserPrompt(
        JNIEnv *env, jobject, jstring jPrompt, jint nPredict)
    {
        // Аналогично nativeSetSystemPrompt — guard от race c unloadModel.
        if (!g_model || !g_context || !g_chat_templates) {
            LOGe("nativeProcessUserPrompt: model not loaded (race with unload?)");
            return 1;
        }

        g_cached_chars.clear();
        g_spec_buffer.clear();
        g_spec_buffer_pos = 0;
        g_next_logit_idx = -1;
        g_assistant_ss.str("");
        g_stop_pos = 0;

        const char *raw = env->GetStringUTFChars(jPrompt, nullptr);
        std::string prompt(raw);
        env->ReleaseStringUTFChars(jPrompt, raw);

        g_thinking_disabled_by_context = false;

        bool has_tmpl = common_chat_templates_was_explicit(g_chat_templates.get());
        std::string formatted = has_tmpl ? chat_add_and_format("user", prompt) : prompt;

        auto tokens = common_tokenize(g_context, formatted, has_tmpl, has_tmpl);

        int n = (int)tokens.size();

        int threshold = (g_thinking_mode == 2) ? RESET_THRESHOLD_NORMAL : RESET_THRESHOLD_THINKING;
        int space_available = g_n_ctx - OVERFLOW_HEADROOM - g_current_pos - n;

        // =====================================================================
        // TODO(memory-system, B.1): УДАЛИТЬ ПРИ ИМПЛЕМЕНТАЦИИ Phase 4 EVICTION
        // ---------------------------------------------------------------------
        // Этот блок — деструктивный fallback на нехватку контекста. История чата
        // полностью теряется (только system prompt сохраняется), что недопустимо
        // при наличии долговременной памяти.
        //
        // План замены (см. model-workshop/architecture.md, Фаза 4):
        //   - При space_available < threshold вместо wipe вернуть error code,
        //     Kotlin orchestrator увидит и запустит eviction
        //   - Phase 4 Этап B-D компрессирует старые сообщения в summary через LLM,
        //     извлекает факты, сохраняет в БД, перестраивает KV из
        //     [system + summary_new + last-N]
        //   - Старая история не теряется, только переходит из KV в summary+facts
        //
        // До Phase 4 этот блок остаётся как safety net на OOM — лучше потерять
        // историю чем крашнуть приложение.
        // =====================================================================
        // Proactive reset: if not enough room for a full response, clear KV cache
        // and re-inject the system prompt. Old conversation is lost — eventually
        // this will be replaced with summarisation + fact retrieval.
        if (space_available < threshold)
        {
            LOGi("Proactive reset: %d tokens left after prompt < threshold %d",
                 space_available, threshold);
            llama_memory_clear(llama_get_memory(g_context), false);
            g_current_pos = 0;
            g_system_pos = 0;
            g_chat_msgs.clear();
            g_token_history.clear();

            // Re-inject system prompt
            if (!g_system_prompt_text.empty())
            {
                auto sys_tokens = common_tokenize(g_context, g_system_prompt_text, true, false);
                if (decode_in_batches(sys_tokens, 0) == 0)
                {
                    g_current_pos = (int)sys_tokens.size();
                    g_system_pos = g_current_pos;
                    // Sync g_token_history with KV (invariant: history == decoded tokens
                    // in same order). Без этого последующий cancelAndCleanKV сделает
                    // clear+redecode только user tokens, потеряв system prompt в KV.
                    g_token_history.insert(g_token_history.end(),
                                           sys_tokens.begin(), sys_tokens.end());
                }
            }
            // TODO (после реализации памяти): re-inject summary + retrieved facts
        }

        g_token_history.insert(g_token_history.end(), tokens.begin(), tokens.end());

        if (n > g_n_ctx - OVERFLOW_HEADROOM)
        {
            tokens.resize(g_n_ctx - OVERFLOW_HEADROOM);
            LOGw("User prompt truncated from %d to %d tokens", n, (int)tokens.size());
            n = (int)tokens.size();
        }

        if (decode_in_batches(tokens, g_current_pos, true))
            return 2;

        g_current_pos += n;

        // Snapshot pre-generation position so cancelAndCleanKV can revert if user
        // hits cancel mid-generation. From here on, g_current_pos will advance per
        // each generated token; cancel must seq_rm [g_pos_before_generation, g_current_pos).
        g_pos_before_generation = g_current_pos;

        // Clamp nPredict so generation can't exceed hard context limit
        int remaining = g_n_ctx - OVERFLOW_HEADROOM - g_current_pos;
        if (remaining < nPredict)
        {
            LOGi("Clamped nPredict from %d to %d (context running low)", nPredict, std::max(remaining, 0));
            nPredict = std::max(remaining, 0);
        }
        g_stop_pos = g_current_pos + nPredict;
        LOGi("User prompt processed: %d tokens, will generate up to pos %d", n, g_stop_pos);
        return 0;
    }

    // --- generateNextToken ---
    JNIEXPORT jstring JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeGenerateToken(
        JNIEnv *env, jobject)
    {
        // ── 1. Return buffered speculative tokens first ──
        if (g_spec_buffer_pos < g_spec_buffer.size())
        {
            llama_token id = g_spec_buffer[g_spec_buffer_pos++];

            if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id))
                return nullptr;

            std::string piece = common_token_to_piece(g_context, id);
            g_cached_chars += piece;

            if (is_valid_utf8(g_cached_chars.c_str()))
            {
                jstring result = env->NewStringUTF(g_cached_chars.c_str());
                g_assistant_ss << g_cached_chars;
                g_cached_chars.clear();
                return result;
            }
            return env->NewStringUTF("");
        }

        // ── 2. Context overflow / stop checks ──
        if (g_current_pos >= g_n_ctx - OVERFLOW_HEADROOM)
        {
            if (!shift_context())
                return nullptr; // hard limit — end generation
        }

        if (g_current_pos >= g_stop_pos)
            return nullptr;

        // ── 3. Sample next token ──
        llama_token id = common_sampler_sample(g_sampler, g_context, g_next_logit_idx);
        g_next_logit_idx = -1;
        common_sampler_accept(g_sampler, id, true);
        g_token_history.push_back(id);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id))
            return nullptr;

        // ── 4. Try speculative drafting ──
        // =====================================================================
        // TODO(memory-system, B.5): SPECULATIVE DECODING — ROOT CAUSE CONFIRMED
        // ---------------------------------------------------------------------
        // Root cause: llama_memory_seq_rm для M-RoPE моделей (Qwen3.5) НЕ обновляет
        // position tracker memory module'а корректно. Подтверждено device-тестом
        // в A.1 cancel cleanup на Pixel 9 Vulkan: после seq_rm tail trim'а
        // следующий decode падает с "for M-RoPE, it is required that the position
        // satisfies: X < Y", где X = stale tracker value.
        //
        // Spec decoding использует ТОТ ЖЕ паттерн (rejection of drafted tokens
        // via seq_rm на specific позициях) — fundamentally блокировано M-RoPE
        // invariant'ом. Не Vulkan-specific, не починится workaround'ом на JNI.
        //
        // Что нужно для re-enable (см. architecture.md Phase 3 «Implementation
        // constraint: M-RoPE и llama_memory_seq_rm»):
        //   1. Upstream llama.cpp fix для seq_rm + M-RoPE position tracker
        //   2. Verification через C.2 тест (analogue of A.1 but for rejection
        //      pattern, not tail trim) — должен дать cosine ≥ 0.999
        //   3. Только тогда re-enable spec decoding
        //
        // Потенциальный gain: 1.5-2× generation speed на mid-range устройствах.
        // Отслеживать: PRs в ggml-org/llama.cpp с упоминаниями seq_rm + mrope/imrope.
        // =====================================================================
        llama_tokens draft;
        // if (!in_soft_zone)
        //     draft = common_ngram_simple_draft(g_ngram_config, g_token_history, id);

        // Limit draft to not exceed stop position or context
        int budget = std::min(
            (int)(g_stop_pos - g_current_pos - 1),
            (int)(g_n_ctx - OVERFLOW_HEADROOM - g_current_pos - 1));
        if ((int)draft.size() > budget)
            draft.resize(std::max(budget, 0));

        int n_accepted = 0;

        if (!draft.empty())
        {
            // ── 5. Batch decode: sampled token + all draft tokens ──
            common_batch_clear(g_batch);
            common_batch_add(g_batch, id, g_current_pos, {0}, true);
            for (int i = 0; i < (int)draft.size(); i++)
                common_batch_add(g_batch, draft[i], g_current_pos + 1 + i, {0}, true);

            if (llama_decode(g_context, g_batch) != 0)
            {
                LOGe("Speculative batch decode failed, falling back to single");
                common_batch_clear(g_batch);
                common_batch_add(g_batch, id, g_current_pos, {0}, true);
                llama_decode(g_context, g_batch);
                g_current_pos++;
                goto return_token;
            }

            // ── 6. Verify draft tokens via greedy check ──
            const auto *vocab = llama_model_get_vocab(g_model);
            const int n_vocab = llama_vocab_n_tokens(vocab);

            for (int i = 0; i < (int)draft.size(); i++)
            {
                // logits[i] = prediction after the i-th batch entry
                // logits[0] predicts what follows 'id' → verify draft[0]
                // logits[1] predicts what follows draft[0] → verify draft[1]
                const float *logits = llama_get_logits_ith(g_context, i);

                llama_token best = 0;
                for (int v = 1; v < n_vocab; v++)
                {
                    if (logits[v] > logits[best])
                        best = v;
                }

                if (best == draft[i] && !llama_vocab_is_eog(vocab, draft[i]))
                {
                    n_accepted++;
                    common_sampler_accept(g_sampler, draft[i], true);
                    g_token_history.push_back(draft[i]);
                }
                else
                {
                    break;
                }
            }

            // ── 7. Clean up rejected tokens from KV cache ──
            if (n_accepted < (int)draft.size())
            {
                llama_pos rm_start = g_current_pos + 1 + n_accepted;
                llama_pos rm_end = g_current_pos + 1 + (int)draft.size();
                llama_memory_seq_rm(llama_get_memory(g_context), 0, rm_start, rm_end);
                // Sample from correct logit position next time
                g_next_logit_idx = n_accepted;
            }

            g_current_pos += 1 + n_accepted;

            // ── 8. Buffer accepted draft tokens ──
            g_spec_buffer.clear();
            g_spec_buffer_pos = 0;
            for (int i = 0; i < n_accepted; i++)
                g_spec_buffer.push_back(draft[i]);

            if (n_accepted > 0)
                LOGi("Spec: drafted %zu, accepted %d", draft.size(), n_accepted);
        }
        else
        {
            // ── No draft — normal single-token decode ──
            common_batch_clear(g_batch);
            common_batch_add(g_batch, id, g_current_pos, {0}, true);
            if (llama_decode(g_context, g_batch) != 0)
            {
                LOGe("llama_decode failed during generation");
                return nullptr;
            }
            g_current_pos++;
        }

    return_token:
        // ── Return the sampled token ──
        std::string piece = common_token_to_piece(g_context, id);
        g_cached_chars += piece;

        if (is_valid_utf8(g_cached_chars.c_str()))
        {
            jstring result = env->NewStringUTF(g_cached_chars.c_str());
            g_assistant_ss << g_cached_chars;
            g_cached_chars.clear();
            return result;
        }
        return env->NewStringUTF("");
    }

    // --- systemInfo ---
    JNIEXPORT jstring JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSystemInfo(
        JNIEnv *env, jobject)
    {
        return env->NewStringUTF(llama_print_system_info());
    }

    // --- backendName ---
    JNIEXPORT jstring JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeBackendName(
        JNIEnv *env, jobject)
    {
        std::string result = g_using_gpu ? "Vulkan + CPU" : "CPU";
        return env->NewStringUTF(result.c_str());
    }

    // --- unload ---
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeUnload(
        JNIEnv *, jobject)
    {
        reset_chat_state(false);
        if (g_sampler)
        {
            common_sampler_free(g_sampler);
            g_sampler = nullptr;
        }
        g_chat_templates.reset();
        if (g_batch_initialized)
        {
            llama_batch_free(g_batch);
            g_batch_initialized = false;
        }
        if (g_context)
        {
            llama_free(g_context);
            g_context = nullptr;
        }
        if (g_model)
        {
            llama_model_free(g_model);
            g_model = nullptr;
        }
        LOGi("Model unloaded");
    }

    // --- shutdown ---
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeShutdown(
        JNIEnv *, jobject)
    {
        llama_backend_free();
        LOGi("Backend shut down");
    }

    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSetThinkingMode(
        JNIEnv *, jobject, jint mode)
    {
        g_thinking_mode = (int)mode;
        g_thinking_disabled_by_context = false;
    }

    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeGetCurrentPos(
        JNIEnv *, jobject)
    {
        return (jint)g_current_pos;
    }

    JNIEXPORT jboolean JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeWasThinkingDisabled(
        JNIEnv *, jobject)
    {
        return (jboolean)g_thinking_disabled_by_context;
    }

    // Signal cooperative cancellation of an in-progress nativeLoadModel.
    // Sets g_cancel_load = true, которое model_load_progress_callback увидит на
    // следующем вызове (typically within milliseconds) и вернёт false → llama.cpp
    // abort'ит загрузку → nativeLoadModel возвращает code 99 (cancelled).
    // No-op если load не идёт.
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeCancelLoading(
        JNIEnv *, jobject)
    {
        g_cancel_load = true;
        LOGi("nativeCancelLoading: flag set, current load (if any) will abort");
    }

    // =============================================================================
    // nativeAddMessageToHistory — декодирует существующее сообщение в KV cache
    // БЕЗ запуска generation. Используется при chat switching (Stage 1) для replay
    // истории чата: после setSystemPrompt пробегаем все исторические messages
    // в порядке (user, assistant, user, assistant, ...) и декодируем каждое.
    //
    // Params:
    //   role     — "user" или "assistant". Влияет на template formatting.
    //   text     — content сообщения. Для assistant — clean text без <think>.
    //
    // Returns:
    //   0     — успех
    //  -1     — модель не загружена
    //  -2     — нет места в context (history превышает g_n_ctx)
    //  -3     — decode failed
    //
    // Side effects:
    //   - g_current_pos продвигается на n tokens
    //   - g_token_history расширяется decoded токенами
    //   - g_pos_before_generation / g_stop_pos НЕ трогаются (это для текущего turn'а)
    // =============================================================================
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeAddMessageToHistory(
        JNIEnv *env, jobject, jstring jRole, jstring jText)
    {
        if (!g_model || !g_context) {
            LOGe("AddMessageToHistory: model not loaded");
            return -1;
        }

        const char *rawRole = env->GetStringUTFChars(jRole, nullptr);
        const char *rawText = env->GetStringUTFChars(jText, nullptr);
        std::string role(rawRole ? rawRole : "");
        std::string text(rawText ? rawText : "");
        env->ReleaseStringUTFChars(jRole, rawRole);
        env->ReleaseStringUTFChars(jText, rawText);

        if (role.empty() || (role != "user" && role != "assistant")) {
            LOGe("AddMessageToHistory: invalid role '%s' (must be 'user' or 'assistant')",
                 role.c_str());
            return -1;
        }
        if (text.empty()) {
            // Empty content всё ещё валиден (например модель ответила пустотой),
            // но не имеет смысла что-то декодировать. No-op.
            return 0;
        }

        bool has_tmpl = common_chat_templates_was_explicit(g_chat_templates.get());
        std::string formatted = has_tmpl ? chat_format_for_history(role, text) : text;
        auto tokens = common_tokenize(g_context, formatted, false, false);
        int n = (int)tokens.size();

        if (g_current_pos + n >= g_n_ctx - OVERFLOW_HEADROOM) {
            LOGw("AddMessageToHistory: insufficient context space "
                 "(current=%d + %d >= %d - %d) — replay would overflow",
                 (int)g_current_pos, n, g_n_ctx, OVERFLOW_HEADROOM);
            return -2;
        }

        if (decode_in_batches(tokens, g_current_pos) != 0) {
            LOGe("AddMessageToHistory: decode failed for role=%s, len=%d", role.c_str(), n);
            return -3;
        }

        g_current_pos += n;
        g_token_history.insert(g_token_history.end(), tokens.begin(), tokens.end());
        LOGi("AddMessageToHistory: role=%s, %d tokens decoded, current_pos=%d",
             role.c_str(), n, (int)g_current_pos);
        return 0;
    }

    // =============================================================================
    // Cancel cleanup: после прерванной генерации (пользователь нажал stop) KV хранит
    // частично сгенерированные токены (обрывок thinking без </think>, half-answer,
    // и т.п.). Без cleanup'а следующий nativeProcessUserPrompt декодирует новый
    // user message ПОВЕРХ этого мусора, и модель видит свой обрывок как историю —
    // confuses follow-up generation.
    //
    // Fix: seq_rm удаляет токены [g_pos_before_generation, g_current_pos), KV
    // возвращается в state до начала generation'а. Также:
    //   - g_token_history trim'ается до той же позиции (инвариант синхронизации)
    //   - common_sampler_reset сбрасывает penalty buffer чтобы next turn не имел
    //     stale token bias от cancelled output'а
    //
    // Safe no-op если g_current_pos == g_pos_before_generation (cancel до первого
    // generated token'а).
    // =============================================================================
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeCancelAndCleanKV(
        JNIEnv *, jobject)
    {
        if (!g_context || g_pos_before_generation == 0) {
            return;
        }
        if (g_current_pos <= g_pos_before_generation) {
            // Cancel сработал до первого сгенерированного токена — KV чистый.
            return;
        }

        const llama_pos start = g_pos_before_generation;
        const llama_pos end = g_current_pos;
        const int n_generated = (int)(end - start);

        // llama_memory_seq_rm НЕ работает корректно для M-RoPE моделей (Qwen3.5):
        // удаляет токены из physical KV cache но НЕ обновляет position tracker
        // memory module'а. Следующий decode падает с "X = N > Y = M, for M-RoPE
        // it is required X < Y". См. также g_can_shift = false detection выше:
        // M-RoPE invariant блокирует любые non-linear position manipulations.
        //
        // Workaround: llama_memory_clear (полная очистка — работает) + re-decode
        // обрезанной истории. Стоимость = prefill (start) токенов:
        //   - Pixel Vulkan: 0.5-3 сек на 1500-2000 tok history
        //   - A80 CPU: 5-20 сек на ту же длину
        // Cancel — редкое событие, slowdown приемлем.
        if (!g_can_shift) {
            LOGi("CancelAndCleanKV: M-RoPE model detected, using clear+redecode "
                 "(discarding %d generated tokens, re-decoding %d history tokens)",
                 n_generated, (int)start);

            // Trim history first — generated tokens отброшены
            if ((size_t)start < g_token_history.size()) {
                g_token_history.resize((size_t)start);
            }

            // Full KV wipe (single primitive that works on M-RoPE + Vulkan)
            llama_memory_clear(llama_get_memory(g_context), false);
            g_current_pos = 0;

            // Re-decode pre-generation history (system prompt + all decoded user msgs
            // and assistant responses up to the cancelled turn's user message).
            if (!g_token_history.empty()) {
                if (decode_in_batches(g_token_history, 0) != 0) {
                    LOGe("CancelAndCleanKV: history re-decode FAILED — KV is now empty, "
                         "chat will need full reset on next message");
                    g_token_history.clear();
                    g_system_pos = 0;
                    g_current_pos = 0;
                } else {
                    g_current_pos = (llama_pos)g_token_history.size();
                    LOGi("CancelAndCleanKV: re-decoded %d history tokens, current_pos=%d",
                         (int)g_token_history.size(), (int)g_current_pos);
                }
            }
        } else {
            // Standard RoPE model — seq_rm для tail-trim работает (теоретически).
            // Этот путь не используется в текущем приложении (только Qwen3.5),
            // но оставлен для будущих моделей.
            LOGi("CancelAndCleanKV: standard RoPE, using seq_rm tail trim [%d, %d)",
                 (int)start, (int)end);
            llama_memory_seq_rm(llama_get_memory(g_context), 0, start, end);
            if ((size_t)start < g_token_history.size()) {
                g_token_history.resize((size_t)start);
            }
            g_current_pos = start;
        }

        // Sampler holds internal state (repetition penalty cache, etc.). Reset
        // чтобы next turn'а не повлиял отброшенный output.
        if (g_sampler) {
            common_sampler_reset(g_sampler);
        }

        // Speculative buffer (если когда-нибудь re-enable'нём) — тоже очистить.
        g_spec_buffer.clear();
        g_spec_buffer_pos = 0;
        g_next_logit_idx = -1;
        g_cached_chars.clear();

        // Этот snapshot теперь не валиден — следующий nativeProcessUserPrompt
        // выставит новый. До тех пор cancelAndCleanKV ничего не сделает (no-op).
        g_pos_before_generation = 0;
    }

    // =============================================================================
    // Stage 2: KV state persistence — native primitives + self-test
    //
    // Используют llama_state_seq_save_file / _load_file. На большинстве backend'ов
    // это работает корректно (verified test G1: cosine 1.000000 across 30 cycles
    // save/restore на CPU). На Vulkan + offload_kqv=true (текущий default) — НЕ
    // работает (cosine ~0.96, KV частично живёт в GPU buffer'ах которые
    // state_seq_save_file не сериализует). См. architecture.md § Backend
    // compatibility, и Phase 5 «Стратегия B».
    //
    // Решение — auto-detection через nativeStatePersistTest: декодируем тестовый
    // prompt, сохраняем state, очищаем KV, загружаем state, декодируем probe-токен
    // в обоих случаях (после save и после load) и сравниваем cosine логитов.
    // Если ≥ 0.999 → backend поддерживает persistence (Strategy A). Иначе — нет
    // (Strategy B, skip file persistence, full re-decode at chat entry).
    //
    // Сам тест выполняется ОДИН РАЗ на bootstrap после loadModel и ДО setSystemPrompt
    // (т.к. модифицирует g_token_history и g_current_pos). Caller (Kotlin) обязан
    // вызвать setSystemPrompt после теста.
    // =============================================================================

    // Сохраняет state sequence 0 в файл. g_token_history передаётся как tokens-array
    // (нужно llama.cpp чтобы при load вернуть тот же набор tokens — используется
    // для verification).
    //
    // Returns: 0 success, 1 model not loaded, 2 save failed (0 bytes written)
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeStateSaveFile(
        JNIEnv *env, jobject, jstring jPath)
    {
        if (!g_model || !g_context) {
            LOGe("nativeStateSaveFile: model not loaded");
            return 1;
        }
        const char *path = env->GetStringUTFChars(jPath, nullptr);
        const size_t bytes = llama_state_seq_save_file(
            g_context, path, /*seq_id=*/0,
            g_token_history.data(), g_token_history.size());
        env->ReleaseStringUTFChars(jPath, path);

        if (bytes == 0) {
            LOGe("nativeStateSaveFile: llama_state_seq_save_file returned 0 (write failed)");
            return 2;
        }
        LOGi("nativeStateSaveFile: wrote %zu bytes, %zu tokens", bytes, g_token_history.size());
        return 0;
    }

    // Загружает state sequence 0 из файла. После успешного load:
    //   - g_token_history заменяется tokens'ами из файла
    //   - g_current_pos выставляется = n_tokens
    //   - g_system_pos НЕ обновляется (caller знает структуру и обновит сам через
    //     nativeSetSystemPos). Для snapshot_base (содержит только system) caller
    //     вызывает setSystemPos(getCurrentPos()). Для per-chat kv_cache (содержит
    //     [system + ...]) caller подставляет известный system prompt token count.
    //
    // Returns: 0 success, 1 model not loaded, 2 load failed
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeStateLoadFile(
        JNIEnv *env, jobject, jstring jPath)
    {
        if (!g_model || !g_context) {
            LOGe("nativeStateLoadFile: model not loaded");
            return 1;
        }
        const char *path = env->GetStringUTFChars(jPath, nullptr);

        // Capacity = g_n_ctx is upper bound — saved state cannot exceed context.
        std::vector<llama_token> tokens_buf(g_n_ctx);
        size_t n_loaded = 0;
        const size_t bytes = llama_state_seq_load_file(
            g_context, path, /*dest_seq_id=*/0,
            tokens_buf.data(), tokens_buf.size(), &n_loaded);
        env->ReleaseStringUTFChars(jPath, path);

        if (bytes == 0) {
            LOGe("nativeStateLoadFile: llama_state_seq_load_file returned 0 (read failed or invalid file)");
            return 2;
        }

        g_token_history.assign(tokens_buf.begin(), tokens_buf.begin() + n_loaded);
        g_current_pos = (llama_pos)n_loaded;
        // Sampler state stale after load — reset (penalty buffer, etc.)
        if (g_sampler) {
            common_sampler_reset(g_sampler);
        }
        LOGi("nativeStateLoadFile: read %zu bytes, %zu tokens, current_pos=%d",
             bytes, n_loaded, (int)g_current_pos);
        return 0;
    }

    // Выставляет g_system_pos вручную. Нужно после nativeStateLoadFile чтобы
    // shift_context / proactive_reset знали сколько токенов в начале — system
    // prompt. Для snapshot_base.bin (только system) → setSystemPos(getCurrentPos()).
    // Для per-chat kv_cache.bin (Stage 2.2.2) → setSystemPos(saved_system_token_count).
    //
    // Без этого вызова после load: g_system_pos = 0, shift_context discard'ит
    // system prompt тоже, proactive_reset re-decode'ит system поверх loaded state.
    JNIEXPORT void JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSetSystemPos(
        JNIEnv *, jobject, jint pos)
    {
        g_system_pos = (llama_pos)pos;
        LOGi("nativeSetSystemPos: g_system_pos=%d", (int)g_system_pos);
    }

    // Self-test для определения Strategy A / B (architecture.md § Backend
    // compatibility). Однопроходной cycle:
    //   1. memory_clear (clean start)
    //   2. Decode test prompt P (~250 tokens, varied text — pangrams)
    //   3. Save state to tempPath — snapshot KV в state «после P»
    //   4. Decode probe token X с logits → захватить logits_baseline
    //   5. memory_clear
    //   6. Load state from tempPath — restore KV в state «после P»
    //   7. Decode probe token X с logits → захватить logits_loaded
    //   8. cosine(logits_baseline, logits_loaded)
    //
    // **Single cycle, не multi**: первая попытка с 3 циклами показала что на
    // Vulkan (Pixel 9) cycle 2 hang'ается в save_file после успешного cycle 1
    // (вероятно interaction bug между state_seq_load + memory_clear + новый
    // save). Один cycle стабильно отрабатывает и достаточен — variance gap между
    // «работает» (~1.0) и «сломано» (~0.96 на старом G7) широкий, single shot
    // не даст false-positive.
    //
    // Длинный prompt (~250 tok разнообразного текста) важен потому что Vulkan
    // offload issue проявляется на realistic chat state'ах с заполненными
    // attention layers. Короткий prompt (10 tok) может влезть в один маленький
    // буфер который сериализуется даже на сломанном code path.
    //
    // **Также меряем save_time** — 26-секундный save на Vulkan делает Strategy A
    // неюзабельной даже при cosine=1.0. Возвращаем cosine; save_time доступен
    // через лог. Kotlin сторона решает гейтинг.
    //
    // Side effects (CRITICAL): wipes KV, modifies g_token_history, resets sampler.
    // Caller ДОЛЖЕН вызвать setSystemPrompt после теста. Эта функция всегда
    // возвращает в clean состояние (reset_chat_state в конце).
    //
    // Returns: jdoubleArray[3] = {cosine, save_seconds, load_seconds}. Caller
    // обязан проверять length и null. На ошибке возвращается null или массив
    // где cosine = NaN.
    JNIEXPORT jdoubleArray JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeStatePersistTest(
        JNIEnv *env, jobject, jstring jTempPath)
    {
        // Helper для возврата error-результата (NaN на cosine, нули на times).
        auto make_error_result = [&]() -> jdoubleArray {
            jdoubleArray result = env->NewDoubleArray(3);
            const jdouble values[3] = { std::nan(""), 0.0, 0.0 };
            env->SetDoubleArrayRegion(result, 0, 3, values);
            return result;
        };

        if (!g_model || !g_context) {
            LOGe("nativeStatePersistTest: model not loaded");
            return make_error_result();
        }

        const char *path = env->GetStringUTFChars(jTempPath, nullptr);
        std::string tempPath(path);
        env->ReleaseStringUTFChars(jTempPath, path);

        // Разнообразный pangram-style текст ~250 токенов. Цель — заполнить
        // attention KV diverse patterns'ами (а не однообразным lorem ipsum
        // который слишком predictable). Это catches Vulkan offload issue
        // которое G7 device-тест увидел на realistic chat state'ах.
        const std::string test_prompt =
            "The quick brown fox jumps over the lazy dog. "
            "Pack my box with five dozen liquor jugs. "
            "How vexingly quick daft zebras jump! "
            "Sphinx of black quartz, judge my vow. "
            "Two driven jocks help fax my big quiz. "
            "Bright vixens jump; dozy fowl quack. "
            "Quick wafting zephyrs vex bold Jim. "
            "Waltz, bad nymph, for quick jigs vex. "
            "Glib jocks quiz nymph to vex dwarf. "
            "Junk MTV quiz graced by fox whelps. "
            "Bawds jog, flick quartz, vex nymphs. "
            "How razorback-jumping frogs can level six piqued gymnasts. "
            "The job requires extra pluck and zeal from every young wage earner. "
            "Five quacking zephyrs jolt my wax bed. "
            "Big fjords vex quick waltz nymph. "
            "Vexed nymphs go for quick jigs and waltzes. "
            "Mr. Jock, TV quiz PhD, bags few lynx. "
            "A quick movement of the enemy will jeopardize six gunboats. "
            "The five boxing wizards jump quickly across the silent meadow. "
            "Crazy Fredrick bought many very exquisite opal jewels for his beloved wife.";
        auto test_tokens = common_tokenize(g_context, test_prompt, /*add_bos=*/true, /*parse_special=*/false);
        if (test_tokens.empty()) {
            LOGe("nativeStatePersistTest: tokenize failed");
            return make_error_result();
        }
        const llama_pos n_prompt = (llama_pos)test_tokens.size();

        // Защита от слишком маленького n_ctx: тест prompt + probe + headroom
        // должны влезть. Если нет — не делаем тест (Strategy B по умолчанию).
        if (n_prompt + OVERFLOW_HEADROOM + 1 >= g_n_ctx) {
            LOGw("nativeStatePersistTest: test prompt (%d tok) too large for n_ctx=%d, skipping",
                 (int)n_prompt, g_n_ctx);
            return make_error_result();
        }

        const llama_token probe = test_tokens[0];

        // Lambda для capture logits после decode probe.
        auto capture_probe_logits = [&](std::vector<float> &out) -> bool {
            common_batch_clear(g_batch);
            common_batch_add(g_batch, probe, n_prompt, {0}, /*logits=*/true);
            if (llama_decode(g_context, g_batch) != 0) return false;
            const auto *vocab = llama_model_get_vocab(g_model);
            const int n_vocab = llama_vocab_n_tokens(vocab);
            const float *logits = llama_get_logits_ith(g_context, -1);
            if (!logits) return false;
            out.assign(logits, logits + n_vocab);
            return true;
        };

        auto cosine_of = [](const std::vector<float> &a, const std::vector<float> &b) -> double {
            if (a.size() != b.size()) return std::nan("");
            double dot = 0.0, na = 0.0, nb = 0.0;
            for (size_t i = 0; i < a.size(); i++) {
                const double x = a[i], y = b[i];
                dot += x * y;
                na += x * x;
                nb += y * y;
            }
            const double denom = std::sqrt(na) * std::sqrt(nb);
            return (denom > 0.0) ? (dot / denom) : 0.0;
        };

        const auto *vocab = llama_model_get_vocab(g_model);
        const int n_vocab = llama_vocab_n_tokens(vocab);

        // 1. Fresh decode P
        LOGi("PersistTest [1] memory_clear");
        llama_memory_clear(llama_get_memory(g_context), false);
        g_current_pos = 0;
        g_token_history.clear();
        LOGi("PersistTest [2] decode P (%d tok)", (int)n_prompt);
        if (decode_in_batches(test_tokens, 0) != 0) {
            LOGe("PersistTest: decode P failed");
            reset_chat_state(true);
            std::remove(tempPath.c_str());
            return make_error_result();
        }
        g_current_pos = n_prompt;
        g_token_history.assign(test_tokens.begin(), test_tokens.end());

        // 2. Save state — измеряем время для save-perf gating
        LOGi("PersistTest [3] save_file");
        const auto save_t0 = std::chrono::steady_clock::now();
        const size_t save_bytes = llama_state_seq_save_file(
            g_context, tempPath.c_str(), 0,
            g_token_history.data(), g_token_history.size());
        const auto save_t1 = std::chrono::steady_clock::now();
        const double save_seconds = std::chrono::duration<double>(save_t1 - save_t0).count();
        if (save_bytes == 0) {
            LOGe("PersistTest: save_file returned 0");
            reset_chat_state(true);
            std::remove(tempPath.c_str());
            return make_error_result();
        }
        LOGi("PersistTest [3] saved %zu bytes in %.2f sec", save_bytes, save_seconds);

        // 3. Baseline probe
        LOGi("PersistTest [4] baseline probe decode");
        std::vector<float> logits_baseline;
        if (!capture_probe_logits(logits_baseline)) {
            LOGe("PersistTest: baseline probe failed");
            reset_chat_state(true);
            std::remove(tempPath.c_str());
            return make_error_result();
        }

        // 4. Clear and load — измеряем load_time
        LOGi("PersistTest [5] memory_clear");
        llama_memory_clear(llama_get_memory(g_context), false);
        g_current_pos = 0;
        g_token_history.clear();
        LOGi("PersistTest [6] load_file");
        std::vector<llama_token> tokens_buf(g_n_ctx);
        size_t n_loaded = 0;
        const auto load_t0 = std::chrono::steady_clock::now();
        const size_t load_bytes = llama_state_seq_load_file(
            g_context, tempPath.c_str(), 0,
            tokens_buf.data(), tokens_buf.size(), &n_loaded);
        const auto load_t1 = std::chrono::steady_clock::now();
        const double load_seconds = std::chrono::duration<double>(load_t1 - load_t0).count();
        if (load_bytes == 0 || (llama_pos)n_loaded != n_prompt) {
            LOGe("PersistTest: load_file failed (bytes=%zu, n_loaded=%zu, expected=%d)",
                 load_bytes, n_loaded, (int)n_prompt);
            reset_chat_state(true);
            std::remove(tempPath.c_str());
            return make_error_result();
        }
        LOGi("PersistTest [6] loaded %zu bytes, n_loaded=%zu, in %.2f sec",
             load_bytes, n_loaded, load_seconds);
        g_current_pos = (llama_pos)n_loaded;
        g_token_history.assign(tokens_buf.begin(), tokens_buf.begin() + n_loaded);

        // 5. Loaded probe
        LOGi("PersistTest [7] post-load probe decode");
        std::vector<float> logits_loaded;
        if (!capture_probe_logits(logits_loaded)) {
            LOGe("PersistTest: post-load probe failed");
            reset_chat_state(true);
            std::remove(tempPath.c_str());
            return make_error_result();
        }

        // 6. Cosine
        const double cosine = cosine_of(logits_baseline, logits_loaded);
        LOGi("PersistTest result: cosine=%.6f, save_time=%.2f sec, load_time=%.2f sec, "
             "size=%zu bytes (n_prompt=%d, n_vocab=%d)",
             cosine, save_seconds, load_seconds, save_bytes, (int)n_prompt, n_vocab);

        // Cleanup
        reset_chat_state(true);
        std::remove(tempPath.c_str());

        jdoubleArray result = env->NewDoubleArray(3);
        const jdouble values[3] = { cosine, save_seconds, load_seconds };
        env->SetDoubleArrayRegion(result, 0, 3, values);
        return result;
    }

    // =============================================================================
    // Stage 5 — GBNF grammar support
    //
    // nativeSetGrammar(gbnf) — пересоздаёт sampler с заданной grammar. Generation
    // через nativeGenerateToken будет ограничена правилами GBNF (например forced
    // JSON output для eviction extraction, ADD|UPDATE|NOOP для conflict resolution).
    //
    // nativeClearGrammar() — возвращает sampler в unconstrained состояние.
    //
    // Caller responsibility: вызывать set/clear до начала generation flow (т.е. до
    // nativeProcessUserPrompt). Менять grammar mid-generation не безопасно — sampler
    // hold'ит state, грамматика влияет на logit filtering.
    //
    // Стоимость re-init sampler'а — миллисекунды, на фоне prefill/generation
    // незначима.
    // =============================================================================

    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSetGrammar(
        JNIEnv *env, jobject, jstring jGrammar)
    {
        if (!g_model) {
            LOGe("nativeSetGrammar: model not loaded");
            return 1;
        }
        const char *raw = env->GetStringUTFChars(jGrammar, nullptr);
        std::string grammar(raw ? raw : "");
        env->ReleaseStringUTFChars(jGrammar, raw);
        recreate_sampler(grammar);
        return 0;
    }

    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeClearGrammar(
        JNIEnv *, jobject)
    {
        if (!g_model) {
            LOGe("nativeClearGrammar: model not loaded");
            return 1;
        }
        recreate_sampler("");
        return 0;
    }

} // extern "C"
