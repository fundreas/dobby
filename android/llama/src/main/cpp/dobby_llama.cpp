// Dobby's JNI shim over llama.cpp.
//
// Ten functions, all jlong handles and jstring. No C++ object crosses the boundary, which is
// what makes ANDROID_STL=c++_static safe here: nothing allocated by this library's copy of the
// standard library is ever freed by anybody else's.
//
// The shape of a request is fixed and small: prefill the system prefix once per process, then
// per utterance truncate the KV cache back to that prefix, decode the utterance, and generate
// under a grammar until the JSON closes. Everything interesting is in the two comments marked
// WHY below — the sampler order and the abort callback.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "dobby-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/// One loaded context, plus everything a request needs to be cancelled and measured.
struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;

    /// Tokens of the system prefix already in the KV cache. The truncation point.
    int32_t n_system = 0;

    /// Set by nativeCancel, read by the abort callback *inside* llama_decode.
    std::atomic<bool> cancelled{false};

    // nativeTimings, reset per request.
    std::atomic<int64_t> prefill_us{0};
    std::atomic<int64_t> decode_us{0};
    std::atomic<int32_t> tokens{0};
    std::atomic<int32_t> resamples{0};
};

/// WHY the abort callback exists.
///
/// A flag checked between tokens cannot stop a decode that is already in flight, and the
/// user-turn prefill is exactly one such call. ggml polls this between graph nodes, so the 5 s
/// deadline holds *during* a decode rather than only at the boundaries between them.
bool abort_if_cancelled(void *data) {
    auto *session = static_cast<Session *>(data);
    return session != nullptr && session->cancelled.load(std::memory_order_relaxed);
}

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars == nullptr ? std::string{} : std::string(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    // A negative return is "this many tokens would have been produced", so one sizing call and
    // one real call, never a guess at the buffer size.
    const int32_t needed = -llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           nullptr, 0, add_special, /*parse_special=*/true);
    if (needed <= 0) return {};
    std::vector<llama_token> tokens(static_cast<size_t>(needed));
    const int32_t written = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                                           tokens.data(), needed, add_special, /*parse_special=*/true);
    if (written < 0) return {};
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string piece(const llama_vocab *vocab, llama_token token) {
    char buffer[256];
    const int32_t written = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, /*special=*/false);
    if (written <= 0) return {};
    return std::string(buffer, static_cast<size_t>(written));
}

/// Decodes `tokens` in chunks of `n_batch`, returning false on error or cancellation.
bool decode_all(Session *session, std::vector<llama_token> &tokens, int32_t n_batch) {
    for (size_t offset = 0; offset < tokens.size(); offset += static_cast<size_t>(n_batch)) {
        const auto chunk = static_cast<int32_t>(
            std::min(static_cast<size_t>(n_batch), tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(tokens.data() + offset, chunk);
        const int32_t status = llama_decode(session->ctx, batch);
        if (status != 0) {
            LOGE("llama_decode returned %d", status);
            return false;
        }
        if (session->cancelled.load(std::memory_order_relaxed)) return false;
    }
    return true;
}

int64_t now_us() {
    return static_cast<int64_t>(ggml_time_us());
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_io_dobby_llama_Llama_nativeInit(JNIEnv *, jobject) {
    llama_backend_init();
    // llama.cpp logs at INFO by default and the model load is several hundred lines of it.
    llama_log_set([](ggml_log_level level, const char *text, void *) {
        if (level >= GGML_LOG_LEVEL_WARN) __android_log_write(ANDROID_LOG_WARN, LOG_TAG, text);
    }, nullptr);
}

JNIEXPORT jlong JNICALL
Java_io_dobby_llama_Llama_nativeLoadModel(JNIEnv *env, jobject, jstring path_, jboolean mlock) {
    const std::string path = to_string(env, path_);
    llama_model_params params = llama_model_default_params();
    // The 1.03 GiB of weights are file-backed pages, not anonymous memory — that is the whole
    // reason a gigabyte model fits next to Parakeet. MLOCK is the lever for cold-after-idle
    // latency (the kernel will drop clean pages during a quiet evening and the next request
    // pays the fault-in), and it is a measurement, not a default.
    params.load_mode = mlock ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MMAP;
    params.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        LOGE("failed to load model from %s", path.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_io_dobby_llama_Llama_nativeFreeModel(JNIEnv *, jobject, jlong handle) {
    if (handle != 0) llama_model_free(reinterpret_cast<llama_model *>(handle));
}

JNIEXPORT jlong JNICALL
Java_io_dobby_llama_Llama_nativeNewContext(JNIEnv *, jobject, jlong model_handle,
                                           jint n_ctx, jint n_batch, jint n_threads) {
    auto *model = reinterpret_cast<llama_model *>(model_handle);
    if (model == nullptr) return 0;

    auto *session = new Session();
    session->model = model;

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(n_ctx);
    params.n_batch = static_cast<uint32_t>(n_batch);
    params.n_ubatch = static_cast<uint32_t>(n_batch / 2 > 0 ? n_batch / 2 : n_batch);
    params.n_threads = n_threads;
    params.n_threads_batch = n_threads;
    params.type_k = GGML_TYPE_F16;
    params.type_v = GGML_TYPE_F16;
    params.abort_callback = abort_if_cancelled;
    params.abort_callback_data = session;

    session->ctx = llama_init_from_model(model, params);
    if (session->ctx == nullptr) {
        LOGE("failed to create context");
        delete session;
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_io_dobby_llama_Llama_nativeFreeContext(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    auto *session = reinterpret_cast<Session *>(handle);
    if (session->ctx != nullptr) llama_free(session->ctx);
    delete session;
}

/// Prefills the system prefix once per process and remembers where it ends.
JNIEXPORT jint JNICALL
Java_io_dobby_llama_Llama_nativePrefillSystem(JNIEnv *env, jobject, jlong handle, jstring prefix_) {
    if (handle == 0) return -1;
    auto *session = reinterpret_cast<Session *>(handle);
    const std::string prefix = to_string(env, prefix_);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    llama_memory_seq_rm(llama_get_memory(session->ctx), 0, 0, -1);
    session->cancelled.store(false, std::memory_order_relaxed);

    std::vector<llama_token> tokens = tokenize(vocab, prefix, /*add_special=*/true);
    if (tokens.empty()) return -1;

    const int64_t started = now_us();
    if (!decode_all(session, tokens, static_cast<int32_t>(tokens.size()))) return -1;
    session->prefill_us.store(now_us() - started, std::memory_order_relaxed);

    session->n_system = static_cast<int32_t>(tokens.size());
    LOGI("prefilled %d system tokens in %lld us", session->n_system,
         static_cast<long long>(session->prefill_us.load()));
    return session->n_system;
}

/// One request: truncate to the system prefix, decode the rest, generate under the grammar.
JNIEXPORT jstring JNICALL
Java_io_dobby_llama_Llama_nativeGenerate(JNIEnv *env, jobject, jlong handle,
                                         jstring tail_, jstring grammar_, jstring root_,
                                         jint max_tokens) {
    if (handle == 0) return nullptr;
    auto *session = reinterpret_cast<Session *>(handle);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    // The prompt cache IS the KV cache: no snapshot and no file.
    //
    // Qwen3-1.7B is 28 layers x 8 KV heads x 128 head_dim = 112 KiB/token at f16, so a ~1100
    // token system prefix snapshots to ~120 MiB held for the life of the service, on top of the
    // KV cache it is a copy of. This is the same thing as "restore the snapshot", implemented
    // as a pointer move. Done at the START of a request rather than the end, so a cancelled,
    // timed-out or crashed one cannot leave the cache dirty for the next.
    llama_memory_seq_rm(llama_get_memory(session->ctx), 0, session->n_system, -1);
    session->cancelled.store(false, std::memory_order_relaxed);
    session->resamples.store(0, std::memory_order_relaxed);
    session->tokens.store(0, std::memory_order_relaxed);

    const std::string tail = to_string(env, tail_);
    const std::string grammar_text = to_string(env, grammar_);
    const std::string root = to_string(env, root_);

    std::vector<llama_token> tokens = tokenize(vocab, tail, /*add_special=*/false);
    if (tokens.empty()) return nullptr;

    const int64_t prefill_started = now_us();
    if (!decode_all(session, tokens, 256)) return nullptr;
    session->prefill_us.store(now_us() - prefill_started, std::memory_order_relaxed);

    llama_sampler *grammar = llama_sampler_init_grammar(vocab, grammar_text.c_str(), root.c_str());
    if (grammar == nullptr) {
        LOGE("grammar failed to parse; refusing to generate unconstrained");
        return nullptr;
    }
    llama_sampler *greedy = llama_sampler_init_greedy();

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> candidates(static_cast<size_t>(n_vocab));
    std::string out;
    const int64_t decode_started = now_us();

    for (int32_t step = 0; step < max_tokens; step++) {
        if (session->cancelled.load(std::memory_order_relaxed)) break;

        const float *logits = llama_get_logits_ith(session->ctx, -1);
        if (logits == nullptr) break;

        // WHY greedy first, and the grammar only on rejection.
        //
        // Applying the grammar sampler to the full candidate set means testing all 151 936
        // vocabulary entries against the grammar's stacks on *every* token — the single
        // per-token cost most likely to blow the budget. llama.cpp's own
        // common_sampler_sample avoids it and this copies that exactly: take the argmax, ask
        // the grammar about that one token, and only fall back to filtering the whole
        // distribution when the grammar says no. On a well-behaved model that happens at the
        // first token (where the model wants prose and the grammar wants '{') and almost
        // nowhere else. nativeTimings counts it so a regression here is visible.
        llama_token chosen = 0;
        float best = logits[0];
        chosen = 0;
        for (int32_t id = 1; id < n_vocab; id++) {
            if (logits[id] > best) {
                best = logits[id];
                chosen = id;
            }
        }

        llama_token_data single{chosen, logits[chosen], 0.0f};
        llama_token_data_array one{&single, 1, -1, false};
        llama_sampler_apply(grammar, &one);
        const bool accepted = single.logit != -INFINITY && !std::isnan(single.logit);

        if (!accepted) {
            session->resamples.fetch_add(1, std::memory_order_relaxed);
            for (int32_t id = 0; id < n_vocab; id++) {
                candidates[static_cast<size_t>(id)] = llama_token_data{id, logits[id], 0.0f};
            }
            llama_token_data_array all{candidates.data(), candidates.size(), -1, false};
            llama_sampler_apply(grammar, &all);
            llama_sampler_apply(greedy, &all);
            if (all.selected < 0) break;
            chosen = all.data[all.selected].id;
        }

        llama_sampler_accept(grammar, chosen);
        session->tokens.fetch_add(1, std::memory_order_relaxed);

        if (llama_vocab_is_eog(vocab, chosen)) break;
        out += piece(vocab, chosen);

        std::vector<llama_token> next{chosen};
        if (!decode_all(session, next, 1)) break;
    }

    session->decode_us.store(now_us() - decode_started, std::memory_order_relaxed);
    llama_sampler_free(greedy);
    llama_sampler_free(grammar);

    if (session->cancelled.load(std::memory_order_relaxed)) return nullptr;
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_io_dobby_llama_Llama_nativeCancel(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    reinterpret_cast<Session *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

/// Real token counts, for calibrating the JVM-side estimator.
JNIEXPORT jint JNICALL
Java_io_dobby_llama_Llama_nativeTokenCount(JNIEnv *env, jobject, jlong handle, jstring text_) {
    if (handle == 0) return -1;
    auto *session = reinterpret_cast<Session *>(handle);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    return static_cast<jint>(tokenize(vocab, to_string(env, text_), /*add_special=*/false).size());
}

/// `prefillUs,decodeUs,tokens,resamples` — parsed by Llama.kt, printed by Tier2ModelTest.
JNIEXPORT jstring JNICALL
Java_io_dobby_llama_Llama_nativeTimings(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("0,0,0,0");
    auto *session = reinterpret_cast<Session *>(handle);
    const std::string out =
        std::to_string(session->prefill_us.load()) + "," +
        std::to_string(session->decode_us.load()) + "," +
        std::to_string(session->tokens.load()) + "," +
        std::to_string(session->resamples.load());
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
