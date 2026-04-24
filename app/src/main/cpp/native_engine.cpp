#include <android/log.h>
#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>
#include <cmath>
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
static constexpr int DEFAULT_CONTEXT_SIZE = 4096;
static constexpr int OVERFLOW_HEADROOM = 4;
static constexpr int N_THREADS_MIN = 2;
static constexpr int N_THREADS_MAX = 6;
static constexpr int N_THREADS_HEADROOM = 2;
static constexpr float DEFAULT_TEMP = 0.3f;
static constexpr int SOFT_LIMIT_THINKING = 1024; // thinking mode — chain-of-thought easily eats 1000+ tokens
static constexpr int SOFT_LIMIT_NORMAL   = 128;  // no thinking — shorter buffer is enough

// --- Global state ---
static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static llama_batch g_batch;
static common_chat_templates_ptr g_chat_templates;
static common_sampler *g_sampler = nullptr;

// Chat state
static std::vector<common_chat_msg> g_chat_msgs;
static llama_pos g_system_pos = 0;
static llama_pos g_current_pos = 0;
static llama_pos g_stop_pos = 0;
static std::string g_cached_chars;
static std::ostringstream g_assistant_ss;
// 0 = AUTO (legacy, model decides), 1 = ALWAYS (force thinking), 2 = NEVER (disable thinking)
static int g_thinking_mode = 0;
static bool g_using_gpu = false; // true if model was loaded with GPU layers
static bool g_thinking_disabled_by_context = false; // true when thinking was auto-disabled due to low context
// Speculative decoding state
static std::vector<llama_token> g_token_history; // all tokens (prompt + generated) for n-gram lookup
static std::vector<llama_token> g_spec_buffer;   // verified draft tokens waiting to be returned
static size_t g_spec_buffer_pos = 0;
static int g_next_logit_idx = -1;      // which logit index to sample from (-1 = last)
static constexpr int SPEC_N_GRAM = 4;  // n-gram size for pattern matching
static constexpr int SPEC_N_DRAFT = 8; // max draft tokens per speculation
static const common_ngram_simple_config g_ngram_config = {SPEC_N_GRAM, SPEC_N_DRAFT};

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

static std::string join_strings(const std::vector<std::string> &v, const std::string &delim)
{
    std::ostringstream s;
    for (size_t i = 0; i < v.size(); i++)
    {
        s << v[i];
        if (i + 1 < v.size())
            s << delim;
    }
    return s.str();
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

static int decode_in_batches(const llama_tokens &tokens, llama_pos start, bool logit_last = false)
{
    for (int i = 0; i < (int)tokens.size(); i += BATCH_SIZE)
    {
        const int n = std::min((int)tokens.size() - i, BATCH_SIZE);
        common_batch_clear(g_batch);

        if (start + i + n >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM)
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

static void reset_chat_state(bool clear_kv = true)
{
    g_chat_msgs.clear();
    g_system_pos = 0;
    g_current_pos = 0;
    g_stop_pos = 0;
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
        JNIEnv *env, jobject, jstring jModelPath, jint nGpuLayers)
    {
        const char *path = env->GetStringUTFChars(jModelPath, nullptr);
        LOGi("Loading model: %s (n_gpu_layers=%d)", path, (int)nGpuLayers);

        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = (int)nGpuLayers;
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
        ctx_params.n_ctx = DEFAULT_CONTEXT_SIZE;
        ctx_params.n_batch = BATCH_SIZE;
        ctx_params.n_ubatch = BATCH_SIZE;
        ctx_params.n_threads = n_threads;
        ctx_params.n_threads_batch = n_threads;
        ctx_params.type_k = GGML_TYPE_Q4_0; // KV cache quantization (default F16)
        ctx_params.type_v = GGML_TYPE_Q4_0; // Qwen3.5 hybrid: only 8/30 layers use KV, likely lossless

        g_context = llama_init_from_model(g_model, ctx_params);
        if (!g_context)
        {
            LOGe("Failed to create context");
            llama_model_free(g_model);
            g_model = nullptr;
            return 2;
        }

        g_batch = llama_batch_init(BATCH_SIZE, 0, 1);
        g_chat_templates = common_chat_templates_init(g_model, "");
        common_params_sampling sparams;
        sparams.temp = DEFAULT_TEMP;
        sparams.penalty_repeat = 1.1f;
        sparams.penalty_last_n = 64;
        g_sampler = common_sampler_init(g_model, sparams);

        detect_shift_support();
        reset_chat_state(false);
        LOGi("Model loaded successfully");
        return 0;
    }

    // --- processSystemPrompt ---
    JNIEXPORT jint JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeSetSystemPrompt(
        JNIEnv *env, jobject, jstring jPrompt)
    {
        reset_chat_state();

        const char *raw = env->GetStringUTFChars(jPrompt, nullptr);
        std::string prompt(raw);
        env->ReleaseStringUTFChars(jPrompt, raw);

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

        if ((int)tokens.size() > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM)
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
        g_cached_chars.clear();
        g_spec_buffer.clear();
        g_spec_buffer_pos = 0;
        g_next_logit_idx = -1;
        g_assistant_ss.str("");
        g_stop_pos = 0;

        const char *raw = env->GetStringUTFChars(jPrompt, nullptr);
        std::string prompt(raw);
        env->ReleaseStringUTFChars(jPrompt, raw);

        // Auto-disable thinking when context is running low:
        // if remaining space < soft limit, thinking would eat all tokens
        // and leave nothing for the visible response.
        // g_thinking_mode stays changed until Kotlin calls setThinkingMode() or
        // next processUserPrompt — so the Kotlin-side parser sees the real mode.
        g_thinking_disabled_by_context = false;
        if (!g_can_shift && g_thinking_mode == 1) // only ALWAYS mode — AUTO uses legacy path
        {
            int space_left = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - g_current_pos;
            if (space_left < SOFT_LIMIT_THINKING)
            {
                LOGi("Context low (%d tokens left) — disabling thinking for this turn",
                     space_left);
                g_thinking_disabled_by_context = true;
                g_thinking_mode = 2; // NEVER for this generation
            }
        }

        bool has_tmpl = common_chat_templates_was_explicit(g_chat_templates.get());
        std::string formatted = has_tmpl ? chat_add_and_format("user", prompt) : prompt;

        auto tokens = common_tokenize(g_context, formatted, has_tmpl, has_tmpl);

        int n = (int)tokens.size();

        // If context is nearly full and we can't shift, reset to system prompt
        if (g_current_pos + n >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM && !g_can_shift)
        {
            LOGw("Context full and shift not supported — resetting to system prompt");
            llama_memory_clear(llama_get_memory(g_context), false);
            g_current_pos = 0;
            g_chat_msgs.clear();
            g_token_history.clear();

            // Re-process system prompt if we had one
            if (g_system_pos > 0)
            {
                // We don't have the original system prompt text, but we have its
                // token count. Unfortunately we need to re-decode it.
                // For now, just reset positions — the system prompt is lost,
                // but at least the chat continues.
                g_system_pos = 0;
            }
        }

        g_token_history.insert(g_token_history.end(), tokens.begin(), tokens.end());

        if (n > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM)
        {
            tokens.resize(DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM);
            LOGw("User prompt truncated from %d to %d tokens", n, (int)tokens.size());
            n = (int)tokens.size();
        }

        if (decode_in_batches(tokens, g_current_pos, true))
            return 2;

        g_current_pos += n;

        // Clamp nPredict so generation can't exceed hard context limit
        int remaining = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - g_current_pos;
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
        if (g_current_pos >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM)
        {
            if (!shift_context())
                return nullptr; // hard limit — end generation
        }

        if (g_current_pos >= g_stop_pos)
            return nullptr;

        // Soft limit: we're running low on context — disable speculative decoding
        // to conserve tokens, but let the model finish its current response
        bool in_soft_zone = (!g_can_shift &&
            g_current_pos >= DEFAULT_CONTEXT_SIZE - get_soft_limit());

        // ── 3. Sample next token ──
        llama_token id = common_sampler_sample(g_sampler, g_context, g_next_logit_idx);
        g_next_logit_idx = -1;
        common_sampler_accept(g_sampler, id, true);
        g_token_history.push_back(id);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id))
            return nullptr;

        // ── 4. Try speculative drafting ──
        llama_tokens draft;
        if (!in_soft_zone)
            draft = common_ngram_simple_draft(g_ngram_config, g_token_history, id);

        // Limit draft to not exceed stop position or context
        int budget = std::min(
            (int)(g_stop_pos - g_current_pos - 1),
            (int)(DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM - g_current_pos - 1));
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

    // --- bench ---
    JNIEXPORT jstring JNICALL
    Java_com_example_adaptivellm_inference_InferenceEngineImpl_nativeBench(
        JNIEnv *env, jobject, jint pp, jint tg, jint pl, jint nr)
    {
        auto *ctx = llama_init_from_model(g_model, [&]()
                                          {
        auto p = llama_context_default_params();
        p.n_ctx = pp;
        p.n_batch = BATCH_SIZE;
        p.n_ubatch = BATCH_SIZE;
        int nt = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
            (int)sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
        p.n_threads = nt;
        p.n_threads_batch = nt;
        return p; }());

        if (!ctx)
        {
            return env->NewStringUTF("Error: failed to create bench context");
        }

        auto batch = llama_batch_init(BATCH_SIZE, 0, 1);
        double pp_avg = 0, tg_avg = 0, pp_std = 0, tg_std = 0;

        for (int r = 0; r < nr; r++)
        {
            // Prompt processing benchmark
            common_batch_clear(batch);
            for (int i = 0; i < pp; i++)
            {
                common_batch_add(batch, 0, i, {0}, false);
            }
            batch.logits[batch.n_tokens - 1] = true;
            llama_memory_clear(llama_get_memory(ctx), false);

            auto t0 = ggml_time_us();
            llama_decode(ctx, batch);
            auto t1 = ggml_time_us();

            // Text generation benchmark
            llama_memory_clear(llama_get_memory(ctx), false);
            auto t2 = ggml_time_us();
            for (int i = 0; i < tg; i++)
            {
                common_batch_clear(batch);
                for (int j = 0; j < pl; j++)
                {
                    common_batch_add(batch, 0, i, {j}, true);
                }
                llama_decode(ctx, batch);
            }
            auto t3 = ggml_time_us();
            llama_memory_clear(llama_get_memory(ctx), false);

            double spp = (double)pp / ((t1 - t0) / 1e6);
            double stg = (double)(pl * tg) / ((t3 - t2) / 1e6);
            pp_avg += spp;
            tg_avg += stg;
            pp_std += spp * spp;
            tg_std += stg * stg;
        }

        llama_batch_free(batch);
        llama_free(ctx);

        pp_avg /= nr;
        tg_avg /= nr;
        if (nr > 1)
        {
            pp_std = sqrt(pp_std / (nr - 1) - pp_avg * pp_avg * nr / (nr - 1));
            tg_std = sqrt(tg_std / (nr - 1) - tg_avg * tg_avg * nr / (nr - 1));
        }
        else
        {
            pp_std = tg_std = 0;
        }

        char desc[128];
        llama_model_desc(g_model, desc, sizeof(desc));
        double size_gb = (double)llama_model_size(g_model) / 1024.0 / 1024.0 / 1024.0;
        double n_params = (double)llama_model_n_params(g_model) / 1e9;

        std::ostringstream out;
        out << std::setprecision(2) << std::fixed;
        out << "Model: " << desc << "\n";
        out << "Size: " << size_gb << " GiB, Params: " << n_params << " B\n";
        out << "PP " << pp << ": " << pp_avg << " t/s\n";
        out << "TG " << tg << ": " << tg_avg << " t/s\n";

        return env->NewStringUTF(out.str().c_str());
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
        llama_batch_free(g_batch);
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

} // extern "C"
