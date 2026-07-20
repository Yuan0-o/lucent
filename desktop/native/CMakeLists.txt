# Desktop (Windows) build of the on-device GGUF assistant native module — see local/LocalLlm.kt.
#
# This compiles the SAME C++ source as the Android build (app/src/main/cpp/lucent_llama.cpp) into a
# Windows DLL, lucent_llama.dll. Two things differ from the Android CMakeLists:
#
#   1. There is no NDK, so <jni.h> comes from the JDK via find_package(JNI), and the one Android-only
#      header the source pulls in (<android/log.h>) is satisfied by the tiny shim in shim/, which maps
#      __android_log_print onto vfprintf(stderr).
#   2. CPU-only. The Vulkan GPU backend is not built on desktop (the requirement's first cut is
#      CPU-only, to keep CI and distribution simple); a portable CPU build runs on any x86-64 Windows.
#
# The JNI symbols the source exports (Java_com_lucent_app_local_LocalLlm_native*) are unchanged,
# because the desktop copy of LocalLlm keeps the Android package name com.lucent.app.local — so no
# renaming is needed and the same .cpp links here verbatim.
#
# llama.cpp is pinned to the same tag (b9888) and, like the Android build, is used from a pre-seeded
# checkout (llama.cpp-src/ beside this file, cloned up front by the workflow with retries) when one
# is present, so there is no network fetch in the middle of the compile.
cmake_minimum_required(VERSION 3.22)
project(lucent_llama C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Static-link the MSVC runtime so the shipped DLL doesn't depend on a VC++ redistributable being
# installed on the end user's machine.
if (MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

include(FetchContent)

# ---- llama.cpp, pinned (same tag as Android) ----
set(LLAMA_CPP_TAG "b9888")

# Prefer a checkout the workflow already made (llama.cpp-src/ beside this file) over a network fetch,
# exactly like the Android CMakeLists. FETCHCONTENT_SOURCE_DIR_<NAME> makes FetchContent use it.
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp-src/CMakeLists.txt")
    set(FETCHCONTENT_SOURCE_DIR_LLAMA_CPP "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp-src" CACHE PATH "" FORCE)
    message(STATUS "lucent(desktop): using pre-seeded llama.cpp at ${FETCHCONTENT_SOURCE_DIR_LLAMA_CPP}")
endif()

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
    GIT_TAG        ${LLAMA_CPP_TAG}
    GIT_SHALLOW    TRUE
)

# Trim llama.cpp to just the library — no tools, tests, server, examples, or libcurl. The app streams
# tokens through JNI and wants nothing else. Static libs, folded into our one shared DLL.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# CPU-only desktop build.
#   GGML_VULKAN OFF  — no GPU backend on desktop for now (CI/distribution simplicity).
#   GGML_NATIVE OFF  — don't bake in -march=native; a portable binary runs on any x86-64 Windows
#                      rather than faulting with an illegal instruction on an older CPU.
#   GGML_OPENMP OFF  — avoid an external OpenMP runtime dependency in the shipped DLL.
set(GGML_VULKAN  OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(llama_cpp)

# ---- JNI headers from the JDK (no NDK on desktop) ----
# find_package(JNI) locates <jni.h> and the platform <jni_md.h> from JAVA_HOME (set by setup-java in
# CI). We only need the headers; nothing is linked against the JVM.
find_package(JNI REQUIRED)

# The shared module the JVM loads. SHARED → lucent_llama.dll on Windows. JNIEXPORT expands to
# __declspec(dllexport) there, and the functions are already extern "C", so the JNI entry points are
# exported with their exact unmangled names.
add_library(lucent_llama SHARED ../../app/src/main/cpp/lucent_llama.cpp)

target_include_directories(lucent_llama PRIVATE
    ${JNI_INCLUDE_DIRS}
    # Satisfies the source's #include <android/log.h> with the desktop shim (stderr logging).
    "${CMAKE_CURRENT_SOURCE_DIR}/shim"
)

target_link_libraries(lucent_llama llama)
