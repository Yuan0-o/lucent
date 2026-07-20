// Desktop shim for <android/log.h>.
//
// lucent_llama.cpp is shared byte-for-byte with the Android build, and its only Android-specific
// dependency is this one logging header: it calls __android_log_print(ANDROID_LOG_INFO/ERROR, TAG,
// fmt, ...) through the LOGI/LOGE macros. On the desktop there is no Android log daemon, so this
// header provides the same symbols and routes them to stderr with vfprintf. That is all the source
// needs to compile and run unchanged on Windows — no edits to the .cpp itself.
#ifndef LUCENT_DESKTOP_SHIM_ANDROID_LOG_H
#define LUCENT_DESKTOP_SHIM_ANDROID_LOG_H

#include <cstdarg>
#include <cstdio>

// Priorities mirror android/log.h's android_LogPriority. Only INFO and ERROR are used by the
// source, but the rest are defined so any incidental reference still compiles.
typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;

// Drop-in for the NDK's __android_log_print. Writes "[TAG] <message>\n" to stderr. The priority is
// accepted for signature compatibility but not otherwise used; errors and info alike go to stderr,
// which is where a desktop app's diagnostics belong.
static inline int __android_log_print(int /*prio*/, const char* tag, const char* fmt, ...) {
    if (tag != nullptr) {
        std::fprintf(stderr, "[%s] ", tag);
    }
    std::va_list args;
    va_start(args, fmt);
    int written = std::vfprintf(stderr, fmt, args);
    va_end(args);
    std::fputc('\n', stderr);
    return written;
}

#endif // LUCENT_DESKTOP_SHIM_ANDROID_LOG_H
