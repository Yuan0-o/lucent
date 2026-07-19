// JNI bridge between Kotlin (local/LocalLlm.kt) and llama.cpp, pinned at tag b9888.
//
// Design rules, in order of importance (matching the task):
//   1. Never crash. Every entry point is wrapped; any native failure surfaces as a Java-side
//      error string or a 0/false return, never a SIGSEGV or an uncaught C++ exception.
//   2. Never block the UI. Kotlin calls everything here from one dedicated background thread
//      (LocalLlm's single-thread dispatcher); this file additionally serializes with a mutex so
//      even a misuse can't race the context.
//   3. Release everything. unload() frees sampler → context → model, and Kotlin calls it when
//      the user leaves the app, so the model's memory is returned the moment the app exits.
//
// Streaming is UTF-8-safe: llama tokens can end mid-codepoint (especially for CJK), so bytes are
// held back until they form complete UTF-8 sequences before being handed to Java — otherwise
// NewStringUTF would corrupt or reject the text and Chinese/Japanese/Korean output would garble.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "LucentLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *   model   = nullptr;
    llama_context * ctx     = nullptr;
    llama_sampler * sampler = nullptr;
    const llama_vocab * vocab = nullptr;
    int             n_ctx   = 0;
    std::mutex      mutex;              // serializes generate/unload on this session
    std::atomic<bool> stop{false};      // set by nativeStop(); checked every token
    std::atomic<bool> busy{false};      // a generation is in flight
};

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        // Quiet llama.cpp's default stderr logging on release devices; keep errors.
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
        }, nullptr);
        llama_backend_init();
    });
}

// How many trailing bytes of `s` form an *incomplete* UTF-8 sequence. Those bytes are held back
// until the next token completes them, so Java only ever receives valid UTF-8.
size_t utf8_incomplete_tail(const std::string & s) {
    size_t n = s.size();
    size_t look = n < 4 ? n : 4;
    for (size_t back = 1; back <= look; ++back) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0x80) == 0x00) return 0;                       // ASCII: complete
        if ((c & 0xC0) == 0xC0) {                               // a lead byte, `back-1` continuations follow
            size_t need = (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
            return back < need ? back : 0;                      // incomplete if fewer bytes than needed
        }
        // else: a continuation byte, keep scanning backwards for its lead
    }
    return 0;
}

// NewStringUTF requires *modified* UTF-8 and aborts the VM on invalid input on some devices.
// Building the String from a byte[] through the String(byte[], "UTF-8") constructor accepts any
// valid UTF-8 (including 4-byte emoji, which modified UTF-8 does not).
jstring to_jstring(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    jstring out = (jstring) env->NewObject(cls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return out;
}

std::string from_jstring(JNIEnv * env, jstring js) {
    if (!js) return "";
    const char * chars = env->GetStringUTFChars(js, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(js, chars);
    return out;
}

std::string piece_of(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n <= 0) return "";
    return std::string(buf, (size_t) n);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text, bool add_special) {
    int guess = (int) text.size() + 16;
    std::vector<llama_token> out((size_t) guess);
    int n = llama_tokenize(vocab, text.c_str(), (int) text.size(), out.data(), guess, add_special, /*parse_special=*/true);
    if (n < 0) {
        out.resize((size_t) -n);
        n = llama_tokenize(vocab, text.c_str(), (int) text.size(), out.data(), -n, add_special, /*parse_special=*/true);
    }
    if (n < 0) n = 0;
    out.resize((size_t) n);
    return out;
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------------------------
// nativeLoad(path, nCtx, nThreads) -> session handle, or 0 on failure
// ---------------------------------------------------------------------------------------------
JNIEXPORT jlong JNICALL
Java_com_lucent_app_local_LocalLlm_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads, jint n_gpu_layers) {
    ensure_backend();
    const std::string path = from_jstring(env, jpath);

    auto * s = new (std::nothrow) Session();
    if (!s) return 0;

    try {
        llama_model_params mparams = llama_model_default_params();
        mparams.use_mmap = true;   // page the weights in lazily; cold RAM cost stays low

        // Layers to offload to the GPU, chosen by the user's CPU/GPU setting (0 = CPU, the safe
        // default; a large value like 999 = offload everything). This only does anything when the
        // build shipped a GPU backend (LUCENT_ENABLE_VULKAN in CMakeLists.txt): with no backend
        // compiled there is nothing to offload to, so the engine stays on CPU no matter the value —
        // which is exactly why the in-app GPU switch is always safe. On Android, GPU (Vulkan) offload
        // is the biggest source of device-specific crashes, so it is opt-in and warned about in the UI.
        mparams.n_gpu_layers = n_gpu_layers > 0 ? n_gpu_layers : 0;

        s->model = llama_model_load_from_file(path.c_str(), mparams);
        if (!s->model) { LOGE("model load failed: %s", path.c_str()); delete s; return 0; }
        s->vocab = llama_model_get_vocab(s->model);

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx           = (uint32_t) (n_ctx > 0 ? n_ctx : 2048);
        cparams.n_batch         = 512;   // prompt is fed in 512-token slices; bounds per-call latency
        cparams.n_threads       = n_threads > 0 ? n_threads : 4;
        cparams.n_threads_batch = n_threads > 0 ? n_threads : 4;

        s->ctx = llama_init_from_model(s->model, cparams);
        if (!s->ctx) { LOGE("context init failed"); llama_model_free(s->model); delete s; return 0; }
        s->n_ctx = (int) llama_n_ctx(s->ctx);

        // Same sampling the cloud path uses (LlmClient: temperature 0.6, top_p 0.9) plus a mild
        // top-k, so switching between cloud and local doesn't change the assistant's character.
        llama_sampler_chain_params sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        s->sampler = llama_sampler_chain_init(sp);
        llama_sampler_chain_add(s->sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_top_p(0.90f, 1));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_temp(0.60f));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        LOGI("loaded %s (n_ctx=%d, threads=%d, gpu_layers=%d)", path.c_str(), s->n_ctx, (int) cparams.n_threads, (int) n_gpu_layers);
        return (jlong) (intptr_t) s;
    } catch (...) {
        LOGE("exception during load");
        if (s->ctx) llama_free(s->ctx);
        if (s->model) llama_model_free(s->model);
        delete s;
        return 0;
    }
}

// ---------------------------------------------------------------------------------------------
// nativeChatPrompt(handle, roles[], texts[], addAssistant) -> templated prompt string
//
// Applies the model's own chat template (Qwen/Llama/Gemma/Phi/ChatML/...). If the model ships a
// template llama.cpp can't render, falls back to plain ChatML, which every instruct model of the
// last two years at least tolerates.
// ---------------------------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_lucent_app_local_LocalLlm_nativeChatPrompt(JNIEnv * env, jobject, jlong handle,
                                                    jobjectArray jroles, jobjectArray jtexts, jboolean add_assistant) {
    auto * s = (Session *) (intptr_t) handle;
    if (!s || !s->model) return to_jstring(env, "");

    jsize n = env->GetArrayLength(jroles);
    if (env->GetArrayLength(jtexts) < n) n = env->GetArrayLength(jtexts);

    std::vector<std::string> roles, texts;
    roles.reserve((size_t) n); texts.reserve((size_t) n);
    for (jsize i = 0; i < n; ++i) {
        auto jr = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        roles.push_back(from_jstring(env, jr));
        texts.push_back(from_jstring(env, jt));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jt);
    }

    std::vector<llama_chat_message> msgs((size_t) n);
    size_t total_chars = 0;
    for (jsize i = 0; i < n; ++i) {
        msgs[(size_t) i] = { roles[(size_t) i].c_str(), texts[(size_t) i].c_str() };
        total_chars += roles[(size_t) i].size() + texts[(size_t) i].size();
    }

    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::string out;
    if (tmpl) {
        std::vector<char> buf(total_chars * 2 + 1024);
        int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        if (len > (int32_t) buf.size()) {
            buf.resize((size_t) len + 1);
            len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), (int32_t) buf.size());
        }
        if (len > 0) out.assign(buf.data(), (size_t) len);
    }
    if (out.empty()) {
        // ChatML fallback — plain, predictable, and understood broadly.
        for (jsize i = 0; i < n; ++i) {
            out += "<|im_start|>" + roles[(size_t) i] + "\n" + texts[(size_t) i] + "<|im_end|>\n";
        }
        if (add_assistant) out += "<|im_start|>assistant\n";
    }
    return to_jstring(env, out);
}

// ---------------------------------------------------------------------------------------------
// nativeGenerate(handle, prompt, maxNew, callback) -> 0 ok / 1 stopped / negative = error
//
// Streams UTF-8-complete pieces to callback.onPiece(String). Each call starts from a clean
// context (llama_memory_clear): the *prompt itself* carries whatever history Kotlin decided to
// include, which keeps this function stateless, simple, and impossible to desynchronize.
// ---------------------------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_lucent_app_local_LocalLlm_nativeGenerate(JNIEnv * env, jobject, jlong handle,
                                                  jstring jprompt, jint max_new, jobject callback) {
    auto * s = (Session *) (intptr_t) handle;
    if (!s || !s->ctx) return -1;

    std::lock_guard<std::mutex> guard(s->mutex);
    s->busy.store(true);
    s->stop.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbClass, "onPiece", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cbClass);
    if (!onPiece) { s->busy.store(false); return -2; }

    int rc = 0;
    try {
        const std::string prompt = from_jstring(env, jprompt);
        std::vector<llama_token> tokens = tokenize(s->vocab, prompt, /*add_special=*/true);
        if (tokens.empty()) { s->busy.store(false); return -3; }

        const int budget = max_new > 0 ? max_new : 512;
        // Keep the *tail* of an over-long prompt: the newest turns matter most, and truncating
        // instead of failing is what "it must not crash" means when a huge history shows up.
        const int max_prompt = s->n_ctx - budget - 8;
        if (max_prompt > 0 && (int) tokens.size() > max_prompt) {
            tokens.erase(tokens.begin(), tokens.end() - max_prompt);
        }

        llama_memory_clear(llama_get_memory(s->ctx), true);
        llama_sampler_reset(s->sampler);

        // Feed the prompt in n_batch-sized slices.
        const int n_batch = 512;
        for (size_t i = 0; i < tokens.size(); i += (size_t) n_batch) {
            if (s->stop.load()) { s->busy.store(false); return 1; }
            int chunk = (int) std::min((size_t) n_batch, tokens.size() - i);
            llama_batch batch = llama_batch_get_one(tokens.data() + i, chunk);
            if (llama_decode(s->ctx, batch) != 0) { s->busy.store(false); return -4; }
        }

        std::string pending;   // bytes held back until they form complete UTF-8
        int produced = 0;
        llama_token tok;
        while (produced < budget && !s->stop.load()) {
            tok = llama_sampler_sample(s->sampler, s->ctx, -1);
            if (llama_vocab_is_eog(s->vocab, tok)) break;

            pending += piece_of(s->vocab, tok);
            size_t hold = utf8_incomplete_tail(pending);
            if (pending.size() > hold) {
                std::string ready = pending.substr(0, pending.size() - hold);
                pending.erase(0, pending.size() - hold);
                jstring jpiece = to_jstring(env, ready);
                if (jpiece) {
                    env->CallVoidMethod(callback, onPiece, jpiece);
                    env->DeleteLocalRef(jpiece);
                    if (env->ExceptionCheck()) { env->ExceptionClear(); s->stop.store(true); }
                }
            }

            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(s->ctx, batch) != 0) { rc = -5; break; }
            ++produced;
        }
        if (s->stop.load() && rc == 0) rc = 1;
    } catch (...) {
        LOGE("exception during generate");
        rc = -6;
    }

    s->busy.store(false);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_lucent_app_local_LocalLlm_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto * s = (Session *) (intptr_t) handle;
    if (s) s->stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_lucent_app_local_LocalLlm_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto * s = (Session *) (intptr_t) handle;
    if (!s) return;
    s->stop.store(true);
    {
        // Wait for any in-flight generation to observe the stop flag and release the mutex, so
        // the context is never freed under a running decode.
        std::lock_guard<std::mutex> guard(s->mutex);
        if (s->sampler) { llama_sampler_free(s->sampler); s->sampler = nullptr; }
        if (s->ctx)     { llama_free(s->ctx);             s->ctx = nullptr; }
        if (s->model)   { llama_model_free(s->model);     s->model = nullptr; }
    }
    delete s;
    LOGI("unloaded — model memory released");
}

} // extern "C"
