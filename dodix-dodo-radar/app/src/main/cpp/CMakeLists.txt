cmake_minimum_required(VERSION 3.22.1)
project(dodollm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_BUILD_TYPE Release)               # llama.cpp в Debug непригоден по скорости
add_compile_options(-O3 -DNDEBUG)

# Параметры llama.cpp/ggml: только CPU, без примеров, статическая линковка
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
# ARMv8.2 + dotprod: подходит всем телефонам с ~2018 г. (в т.ч. Exynos 2100 / S21 Ultra)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

include(FetchContent)
# Версия закреплена: на ней Qwen3.5 (архитектура qwen35) собирается в GGUF и поддерживается.
FetchContent_Declare(llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b9222
    GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)

add_library(dodollm SHARED llm_jni.cpp)
target_link_libraries(dodollm PRIVATE llama ggml log android)
target_link_options(dodollm PRIVATE -Wl,-z,max-page-size=16384)
