// JNI-обёртка над llama.cpp: загрузка GGUF, генерация с GBNF-грамматикой, остановка, выгрузка.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "DodoLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};
static bool g_backend_ready = false;

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 512;
};

static std::string to_std(JNIEnv * env, jstring s) {
    if (!s) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

static jbyteArray to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ru_dodix_dodoradar_llm_LlamaEngine_nativeLoad(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    if (!g_backend_ready) { llama_backend_init(); g_backend_ready = true; }
    std::string path = to_std(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", path.c_str()); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }
    auto * e = new Engine();
    e->model = model; e->ctx = ctx; e->vocab = llama_model_get_vocab(model);
    e->n_ctx = n_ctx; e->n_batch = 512;
    LOGI("model loaded, n_ctx=%d threads=%d", n_ctx, n_threads);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ru_dodix_dodoradar_llm_LlamaEngine_nativeGenerate(JNIEnv * env, jobject, jlong handle, jstring jprompt, jstring jgrammar,
                                                       jint max_tokens, jfloat temp, jint top_k, jfloat top_p, jint seed) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return to_bytes(env, "@@ERR:engine not loaded");
    g_abort = false;

    std::string prompt = to_std(env, jprompt);
    std::string grammar = to_std(env, jgrammar);

    int n = -llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, false, true);
    if (n <= 0) return to_bytes(env, "@@ERR:tokenize failed");
    if (n + max_tokens > e->n_ctx) return to_bytes(env, "@@ERR:prompt too long: " + std::to_string(n) + " tokens");
    std::vector<llama_token> toks((size_t) n);
    if (llama_tokenize(e->vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), false, true) < 0)
        return to_bytes(env, "@@ERR:tokenize failed");

    llama_memory_clear(llama_get_memory(e->ctx), true);

    for (int i = 0; i < n; i += e->n_batch) {
        if (g_abort) return to_bytes(env, "@@ERR:aborted");
        int c = std::min(e->n_batch, n - i);
        llama_batch b = llama_batch_get_one(toks.data() + i, c);
        if (llama_decode(e->ctx, b) != 0) return to_bytes(env, "@@ERR:decode failed (prompt)");
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler * g = llama_sampler_init_grammar(e->vocab, grammar.c_str(), "root");
        if (g) llama_sampler_chain_add(smpl, g); else LOGE("grammar parse failed, generating without grammar");
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist((uint32_t) seed));

    std::string out;
    for (int i = 0; i < max_tokens; i++) {
        if (g_abort) break;
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, t)) break;
        char buf[256];
        int len = llama_token_to_piece(e->vocab, t, buf, (int32_t) sizeof(buf), 0, false);
        if (len > 0) out.append(buf, (size_t) len);
        llama_batch b = llama_batch_get_one(&t, 1);
        if (llama_decode(e->ctx, b) != 0) break;
    }
    llama_sampler_free(smpl);
    return to_bytes(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_ru_dodix_dodoradar_llm_LlamaEngine_nativeAbort(JNIEnv *, jobject) { g_abort = true; }

extern "C" JNIEXPORT void JNICALL
Java_ru_dodix_dodoradar_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

extern "C" JNIEXPORT jstring JNICALL
Java_ru_dodix_dodoradar_llm_LlamaEngine_nativeSystemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}
