cmake_minimum_required(VERSION 3.31.6)

project("adaptive-llm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --- Android ABI detection ---
if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# --- llama.cpp as subdirectory ---
# Project layout: degree/adaptive-llm/app/src/main/cpp/CMakeLists.txt
#                  ^5     ^4          ^3  ^2  ^1   ^0 (current)
# llama.cpp at:   degree/llama.cpp/
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../llama.cpp)

# Ensure MODULE backend libraries (.so plugins built with GGML_BACKEND_DL=ON)
# end up in the same directory where AGP looks for .so files to package into APK.
# Without this, backends are built but not included in the APK.
if(DEFINED CMAKE_LIBRARY_OUTPUT_DIRECTORY)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
endif()

# --- Vulkan support for Android ---
# NDK has Vulkan C headers but not C++ headers (vulkan.hpp).
# Download Vulkan-Headers to provide them, and point glslc to NDK shader-tools.
if(DEFINED ANDROID_NDK AND GGML_VULKAN)
    include(FetchContent)
    FetchContent_Declare(
        VulkanHeaders
        URL https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.275.tar.gz
    )
    FetchContent_MakeAvailable(VulkanHeaders)

    # Make find_package(Vulkan) find the downloaded headers
    set(Vulkan_INCLUDE_DIR "${vulkanheaders_SOURCE_DIR}/include" CACHE PATH "" FORCE)

    # Point to NDK's glslc
    find_program(Vulkan_GLSLC_EXECUTABLE glslc
        PATHS "${ANDROID_NDK}/shader-tools"
        PATH_SUFFIXES windows-x86_64 linux-x86_64 darwin-x86_64
        NO_DEFAULT_PATH
    )
    message(STATUS "Vulkan headers: ${Vulkan_INCLUDE_DIR}")
    message(STATUS "glslc: ${Vulkan_GLSLC_EXECUTABLE}")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

# --- SQLite + sqlite-vec static library ---
# Vendored amalgamations в third_party/. Building как static lib чтобы:
#   - не пересобирать sqlite3.c при изменениях в native_engine.cpp (9.5 MB C — медленно)
#   - изолировать SQLite-специфичные compile flags от llama.cpp / native_engine.cpp
#
# SQLite flags:
#   SQLITE_ENABLE_FTS5 = full-text search (используется в architecture.md facts_fts)
#   SQLITE_THREADSAFE=1 = serialized mode (full thread safety, мы всё равно
#                          сериализуем через dbDispatcher но береженого Бог бережет)
#   SQLITE_DEFAULT_FOREIGN_KEYS=1 = FK on by default (плюс per-connection PRAGMA)
#   SQLITE_OMIT_LOAD_EXTENSION = не используем динамическую загрузку extensions
#                                  (sqlite-vec статически слинкована)
#   SQLITE_OMIT_DEPRECATED = меньше binary size
#   SQLITE_DQS=0 = disable double-quoted strings (SQL standard, защита от typo
#                   "column" вместо 'literal')
#
# sqlite-vec flags:
#   SQLITE_CORE = vec использует sqlite3.h напрямую (не api_routines pointers)
#   SQLITE_VEC_STATIC = не экспортирует символы (мы не shared lib для vec)
add_library(sqlitevec STATIC
    third_party/sqlite/sqlite3.c
    third_party/sqlite-vec/sqlite-vec.c
)

target_compile_definitions(sqlitevec
    PRIVATE
        # Flags for sqlite3.c amalgamation compilation (только sqlite3.c их видит)
        SQLITE_ENABLE_FTS5=1
        SQLITE_THREADSAFE=1
        SQLITE_DEFAULT_FOREIGN_KEYS=1
        SQLITE_OMIT_LOAD_EXTENSION=1
        SQLITE_OMIT_DEPRECATED=1
        SQLITE_DQS=0
        # Symbol visibility для sqlite-vec.c (без dllexport)
        SQLITE_VEC_STATIC
    PUBLIC
        # Критично: SQLITE_CORE определяет include-path в sqlite-vec.h и sqlite3ext.h.
        # Без него consumers (native_db.cpp) подтягивают sqlite3ext.h где SQLite API
        # становится макросами вида `sqlite3_api->open_v2` — но sqlite3_api нет в
        # native_db.cpp (это для dynamic extensions). С SQLITE_CORE все вызовы —
        # прямые в sqlite3.c из нашей статической библиотеки.
        SQLITE_CORE
)

target_include_directories(sqlitevec PUBLIC
    third_party/sqlite
    third_party/sqlite-vec
)

# C99 для sqlite-vec (использует variable-length arrays и compound literals)
set_target_properties(sqlitevec PROPERTIES C_STANDARD 99)

# Suppress некоторых warnings из SQLite amalgamation (legacy C code)
# Это амальгамация upstream, мы не хотим её патчить.
#   -Wno-pointer-bool-conversion: sqlite-vec проверяет `if (sqlite3_mutex_enter)` —
#     это нужно для dynamic-extension режима где функция может быть NULL. С SQLITE_CORE
#     (наш случай) функция всегда есть, компилятор знает что check тривиально true.
#     Поведение корректное, warning избыточен.
target_compile_options(sqlitevec PRIVATE
    -Wno-deprecated-declarations
    -Wno-unused-but-set-variable
    -Wno-unused-function
    -Wno-unused-parameter
    -Wno-pointer-bool-conversion
)

# --- JNI library ---
add_library(${CMAKE_PROJECT_NAME} SHARED
    native_engine.cpp
    native_db.cpp
)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
    GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    llama
    common
    sqlitevec
    android
    log
)
