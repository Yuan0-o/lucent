# Native module for the on-device GGUF assistant (llama.cpp) — see local/LocalLlm.kt.
#
# llama.cpp is fetched at configure time and pinned to a fixed upstream release so builds are
# reproducible. The pin (b9888) is the exact llama.cpp version the attached ollama source tree
# validates against (ollama-main/LLAMA_CPP_VERSION), i.e. a combination known to be stable.
#
# The CPU backend is always built: it works on every device, needs no driver, and its worst case is
# "slow", never "crash". The Vulkan GPU backend is ALSO built by default (LUCENT_ENABLE_VULKAN=ON),
# so the in-app CPU/GPU switch can really offload to the GPU; a device whose driver can't handle it
# falls back to CPU in LocalLlm rather than crashing. Pass -DLUCENT_ENABLE_VULKAN=OFF (Gradle
# -PcpuOnly) for a smaller CPU-only APK. All heavy work runs on a background thread; the UI never
# blocks on this code.
cmake_minimum_required(VERSION 3.22.1)
project(lucent_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# ---- llama.cpp, pinned ----
set(LLAMA_CPP_TAG "b9888")

# If CI (or a developer working offline) has already cloned llama.cpp at the pinned tag into
# llama.cpp-src/ beside this file, use that checkout instead of hitting the network at configure
# time. FetchContent honours FETCHCONTENT_SOURCE_DIR_<NAME>; setting it only when the directory
# actually exists means local builds without the pre-seed keep working exactly as before. This is
# what removes the one network fetch from the middle of the Gradle build — the workflow's
# "Prefetch llama.cpp" step does the clone up front, with retries.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp-src/CMakeLists.txt")
    set(FETCHCONTENT_SOURCE_DIR_LLAMA_CPP "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp-src" CACHE PATH "" FORCE)
    message(STATUS "lucent: using pre-seeded llama.cpp at ${FETCHCONTENT_SOURCE_DIR_LLAMA_CPP}")
endif()

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)

# Trim llama.cpp down to the library itself. No tools, no tests, no server, no libcurl —
# the app streams tokens through JNI, nothing else is wanted.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# CPU is always built (works on every device, no driver, worst case "slow" not "crash").
#
# GPU (Vulkan) is OPT-IN and OFF by default, deliberately: on Android, Vulkan offload is the single
# biggest source of device-specific crashes, and a non-technical maintainer can't debug a CI that
# the biggest source of device-specific crashes, so it is OPT-IN at runtime: the in-app CPU/GPU
# switch (SettingsScreen / LocalLlm) defaults to CPU and warns before turning GPU on. But the GPU
# BACKEND is compiled in by default here, so choosing GPU actually offloads to the GPU. If a device's
# Vulkan driver can't handle it, LocalLlm transparently falls back to CPU (it never crashes the app).
#
# To build a smaller, CPU-only APK instead (e.g. if the Vulkan build ever breaks CI), pass
# -DLUCENT_ENABLE_VULKAN=OFF (the Gradle flag is -PcpuOnly).
option(LUCENT_ENABLE_VULKAN "Compile the Vulkan GPU backend for the on-device model" ON)

set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
if (LUCENT_ENABLE_VULKAN)
    message(STATUS "lucent: Vulkan GPU backend ENABLED")
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    # ggml's Vulkan backend compiles its GLSL shaders to SPIR-V at build time with glslc, run on the
    # BUILD machine (a host tool). Cross-compiling to Android, CMake's FindVulkan often can't locate a
    # host glslc — so point it straight at the one bundled with the NDK we're already using. That
    # needs no download and is always the right version. If it isn't where we expect, we leave
    # Vulkan_GLSLC_EXECUTABLE unset and let CMake find glslc on PATH (the CI provides one as a
    # fallback). ANDROID_NDK and ANDROID_HOST_TAG are set by the NDK's CMake toolchain.
    if (NOT Vulkan_GLSLC_EXECUTABLE AND DEFINED ANDROID_NDK)
        set(_lucent_host_tag "${ANDROID_HOST_TAG}")
        if (NOT _lucent_host_tag)
            set(_lucent_host_tag "linux-x86_64")
        endif()
        set(_lucent_glslc "${ANDROID_NDK}/shader-tools/${_lucent_host_tag}/glslc")
        if (EXISTS "${_lucent_glslc}")
            message(STATUS "lucent: using NDK glslc at ${_lucent_glslc}")
            set(Vulkan_GLSLC_EXECUTABLE "${_lucent_glslc}" CACHE FILEPATH "glslc for shader compilation" FORCE)
        endif()
    endif()
else()
    message(STATUS "lucent: Vulkan GPU backend DISABLED (CPU-only build)")
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()

# armv8 devices without dotprod exist; let ggml's runtime CPU detection handle features rather
# than baking in an -march that faults on older cores (the "must not crash on old devices" rule).
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
endif()

FetchContent_MakeAvailable(llama_cpp)

add_library(lucent_llama SHARED lucent_llama.cpp)

find_library(log-lib log)

target_link_libraries(lucent_llama
    llama
    ${log-lib})
