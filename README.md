# LocaLLM — adaptive on-device LLM с долговременной памятью

Android-приложение для автономного запуска большой языковой модели (Qwen 3.5-4B) **полностью на устройстве**, без интернета и без передачи данных третьим лицам. Адаптивно подбирает квантование под профиль устройства, поддерживает гибридную долговременную память (factual + summary), русский язык из коробки.


- 📱 **APK**: [github.com/Erygfry/adaptive-llm/releases](https://github.com/Erygfry/adaptive-llm/releases)
- 📊 **Бенчмарк**: [github.com/Erygfry/memory-bench](https://github.com/Erygfry/adaptive-llm-memory-bench) *(отдельный репозиторий — корпус сценариев + Python eval)*

---

## Ключевые особенности

- **Off-device по умолчанию.** Модель, факты, чаты — всё в локальной SQLite БД устройства. Никаких сетевых запросов для работы (опциональная Firebase-телеметрия отключаема).
- **Адаптивный выбор квантования.** Профиль устройства (RAM, SoC, CPU/GPU фичи) → автоматический выбор Q3_K_M / Q4_K_M / Q6_K варианта Qwen3.5-4B.
- **Гибридная долговременная память.** Три уровня: контекстное окно → сжатая сводка → семантические факты (SQLite + sqlite-vec + FTS5 trigram). Eviction с extraction фактов через GBNF-grammar.
- **Vulkan / CPU backend.** Автоматический подбор: Vulkan на GPU-устройствах, KleidiAI на ARM CPU.
- **Pruned + дообученная модель.** Базовая Qwen3.5-4B (32→30 layers, 9216 нейронов MLP), пере-квантована с calibration imatrix.

---

## Скачать готовое приложение

Самый простой способ — установить готовый APK с релизов:

1. Перейти на [Releases](https://github.com/Erygfry/adaptive-llm/releases) → скачать последний `.apk`.
2. На Android разрешить установку из неизвестных источников и поставить.
3. При первом запуске приложение определит профиль устройства и предложит подходящую модель к скачиванию (~2-3 ГБ). Скачивание — в самом приложении, не отдельно.

**Минимальные требования:** Android 9+ (API 28), 6 ГБ RAM, ARM64 (aarch64). Реальная производительность зависит от SoC — на Tensor G4 / Snapdragon 8 Gen 2+ комфортная скорость, на старых Snapdragon 7xx — заметные паузы.

---

## Сборка из исходников

### Что понадобится

| Инструмент | Версия | Зачем |
|---|---|---|
| **Android Studio** | Koala (2024.1) или новее | IDE + Android SDK manager |
| **JDK** | 17 (Temurin рекомендуется) | Toolchain для Kotlin/Gradle |
| **Android SDK** | compileSdk 36, build-tools 36 | API уровень 36 (Android 16) |
| **Android NDK** | **29.0.13113456** | Нативная сборка C++ части (llama.cpp + sqlite-vec) |
| **CMake** | **3.31.6** | Билд-система для C++ |
| **Git** | любая | Клонирование зависимостей |
| **llama.cpp** | свежий main | Inference engine (см. ниже про расположение) |

Точные версии NDK и CMake зафиксированы в `app/build.gradle.kts` — Android Studio предложит автоматически скачать через SDK Manager при первом sync.

### Расположение исходников

CMake-скрипт ожидает llama.cpp **как соседний репозиторий** на одном уровне с adaptive-llm:

```
parent-dir/
├── adaptive-llm/         ← этот репозиторий
│   └── app/src/main/cpp/CMakeLists.txt  → ищет ../../../../../llama.cpp
└── llama.cpp/            ← клонировать сюда
```

### Шаги от чистой машины

```bash
# 1. Клонировать adaptive-llm и llama.cpp как соседей
mkdir my-llm-project && cd my-llm-project
git clone https://github.com/Erygfry/adaptive-llm.git
git clone https://github.com/ggml-org/llama.cpp.git

# 2. Открыть adaptive-llm в Android Studio
#    File → Open → выбрать папку adaptive-llm/
#    IDE сама предложит установить недостающий SDK / NDK / CMake через SDK Manager
```

При первом Gradle Sync, Android Studio:
- Подтянет Android SDK 36, NDK 29.0.13113456, CMake 3.31.6 (если их нет).
- Распакует все Gradle-зависимости (Compose, Markwon, ONNX Runtime, Firebase, sqlite-vec...).
- **Vulkan headers** скачаются автоматически через CMake `FetchContent` при первой native-сборке (см. `app/src/main/cpp/CMakeLists.txt`) — отдельно ставить Vulkan SDK не нужно.

После успешного sync:

```bash
# Из корня adaptive-llm
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease        # release APK (требует подписи; см. ниже)
```

Или из IDE: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.

Первая сборка нативной части (llama.cpp + sqlite-vec) занимает 5–10 минут на средней машине — компилируется кросс-компилятором для arm64-v8a. Последующие сборки инкрементальные, ~10–30 секунд.

### Firebase (опционально)

Прод-сборка использует Firebase Analytics, Crashlytics и Firestore для **анонимной телеметрии** (характеристики устройства, скорость генерации, ошибки). Файл `app/google-services.json` намеренно отсутствует в репозитории — это конфиг моего Firebase-проекта.

Варианты:

**A. Собрать без Firebase (рекомендуется для форков).** В [`app/build.gradle.kts`](app/build.gradle.kts):
- Закомментировать плагины `google.services` и `firebase.crashlytics` в `plugins { ... }`.
- Закомментировать Firebase-зависимости в `dependencies { ... }` (блок `// Firebase`).
- В [`analytics/AnalyticsLogger.kt`](app/src/main/java/com/example/adaptivellm/analytics/AnalyticsLogger.kt) тела методов заменить на `Unit` (либо заглушить весь класс).

**B. Поставить свой Firebase.** Создать проект в [Firebase Console](https://console.firebase.google.com/), добавить Android-app с package `com.example.adaptivellm`, скачать `google-services.json` и положить в `app/`. Структура коллекций Firestore — см. `AnalyticsLogger.logToFirestore()`.

### Release-сборка и подпись

Для релизного APK нужно настроить keystore. По умолчанию `assembleRelease` упадёт без подписи.

1. Сгенерировать keystore (если нет): **Build → Generate Signed APK / Bundle** в Android Studio (создаст `.jks` файл).
2. В `app/build.gradle.kts` либо добавить блок `signingConfigs { release { ... } }`, либо настроить в IDE через **File → Project Structure → Signing Configs**.
3. `*.jks`, `*.keystore` зашиты в `.gitignore` — не коммитить.

### Установка на устройство для тестирования

```bash
# Через ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Через Android Studio
# Run → выбрать устройство → ▶
```

**Только arm64-v8a.** `abiFilters` в `app/build.gradle.kts` ограничивает сборку этой ABI — на x86-эмуляторе APK не запустится. Для отладки можно временно добавить `"x86_64"` к фильтрам.

---

## Архитектура (кратко)

```
┌─────────────────────────────────────────────────────────────┐
│ UI (Jetpack Compose + Material3)                            │
│  SetupScreen · ChatList · Chat · FactsMemory · Settings     │
└──────────────────────────┬──────────────────────────────────┘
                           │ MainViewModel (StateFlow)
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
┌──────────────────┐  ┌──────────┐  ┌──────────────────────┐
│ InferenceEngine  │  │ Eviction │  │ MemoryDatabase       │
│ (JNI → llama.cpp)│  │ (GBNF →  │  │ SQLite + sqlite-vec  │
│ Vulkan / CPU     │  │ extract) │  │ + FTS5 trigram       │
└──────────────────┘  └──────────┘  └──────────────────────┘
                           │              ▲
                           ▼              │
                  ┌──────────────────────────────┐
                  │ EmbeddingModel (ONNX)        │
                  │ USER2-small int8 — 384-dim   │
                  └──────────────────────────────┘
```

**Eviction-pipeline:** при превышении `T_max` (адаптивно по RAM-тиру) фоновый поток снимает snapshot текущего KV → запускает LLM с GBNF-граммарой для extraction фактов + обновления summary → новые факты идут в SQLite с эмбеддингом → KV пере-собирается как `[system + summary + last-N]`.

**Retrieval-pipeline:** на каждое сообщение пользователя — гибридный поиск (FTS5 BM25 + sqlite-vec KNN) → RRF merge → triple scoring (relevance + recency + importance) → top-K фактов в промпт.

Полное описание — в дипломной работе и презентации защиты.

---

## Стек

- **Языки**: Kotlin 2.x, C++ 17, Python (для оптимизации модели)
- **UI**: Jetpack Compose, Material3, Compose-MVVM, WorkManager
- **Inference**: llama.cpp (через JNI), Vulkan GPU / KleidiAI CPU backend
- **Память**: SQLite + sqlite-vec + FTS5 (trigram tokenizer)
- **ML embedding**: ONNX Runtime + onnxruntime-extensions (USER2-small int8, tokenizer вшит в граф)
- **Модель**: Qwen 3.5-4B (pruned 32→30 layers, доступна в Q3_K_M / Q4_K_M / Q6_K)
- **Аналитика (опц.)**: Firebase Analytics + Crashlytics + Firestore

---

## Лицензия

Код приложения — MIT. Модель Qwen 3.5 — Apache 2.0 (см. оригинальную лицензию [Qwen](https://github.com/QwenLM/Qwen)). llama.cpp — MIT.

---

