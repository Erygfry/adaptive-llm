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

# --- JNI library ---
add_library(${CMAKE_PROJECT_NAME} SHARED
    native_engine.cpp
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
    android
    log
)
