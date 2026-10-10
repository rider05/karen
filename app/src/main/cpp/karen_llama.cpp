// JNI bridge: Karen chat <-> llama.cpp core (CPU only, blocking calls).
// Kotlin runs every call on Dispatchers.IO and renders the typing effect.
// Long UTF comments use plain ASCII per repo style.

#include <jni.h>

#include <atomic>
#include <random>
#include <string>
#include <vector>

#include "llama.h"

struct Handle {
    llama_model * model = nullptr;
    int n_ctx = 2048;
    int n_threads = 4;
    std::atomic<bool> abort{false};
};

// Abort hooks: ggml polls these inside llama_decode/process, so Stop lands
// mid-chunk instead of after minutes of number crunching.
static bool abort_flag(void * data) {
    return ((std::atomic<bool> *) data)->load();
}

static bool load_progress(float, void * data) {
    return !((std::atomic<bool> *) data)->load();
}

static std::string utf16_to_utf8(const jchar * w, jsize n) {
    std::string s;
    s.reserve((size_t) n * 3);
    for (jsize i = 0; i < n; ++i) {
        uint32_t c = w[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n) {
            uint32_t lo = w[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                ++i;
            }
        }
        if (c < 0x80) {
            s.push_back((char) c);
        } else if (c < 0x800) {
            s.push_back((char) (0xC0 | (c >> 6)));
            s.push_back((char) (0x80 | (c & 0x3F)));
        } else if (c < 0x10000) {
            s.push_back((char) (0xE0 | (c >> 12)));
            s.push_back((char) (0x80 | ((c >> 6) & 0x3F)));
            s.push_back((char) (0x80 | (c & 0x3F)));
        } else {
            s.push_back((char) (0xF0 | (c >> 18)));
            s.push_back((char) (0x80 | ((c >> 12) & 0x3F)));
            s.push_back((char) (0x80 | ((c >> 6) & 0x3F)));
            s.push_back((char) (0x80 | (c & 0x3F)));
        }
    }
    return s;
}

static std::vector<jchar> utf8_to_utf16(const std::string & s) {
    std::vector<jchar> w;
    w.reserve(s.size());
    size_t i = 0;
    while (i < s.size()) {
        uint32_t c = (unsigned char) s[i];
        size_t extra = 0;
        if ((c & 0x80) == 0) {
            extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            c &= 0x1F; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            c &= 0x0F; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            c &= 0x07; extra = 3;
        } else {
            w.push_back((jchar) 0xFFFD); ++i; continue;
        }
        if (i + extra >= s.size()) { w.push_back((jchar) 0xFFFD); break; }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            unsigned char cc = (unsigned char) s[i + k];
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            c = (c << 6) | (cc & 0x3F);
        }
        if (!ok) { w.push_back((jchar) 0xFFFD); ++i; continue; }
        i += 1 + extra;
        if (c > 0x10FFFF) c = 0xFFFD;
        if (c >= 0x10000) {
            c -= 0x10000;
            w.push_back((jchar) (0xD800 + (c >> 10)));
            w.push_back((jchar) (0xDC00 + (c & 0x3FF)));
        } else {
            w.push_back((jchar) c);
        }
    }
    return w;
}

static std::string jstr(JNIEnv * env, jstring js) {
    if (!js) return std::string();
    jsize n = env->GetStringLength(js);
    const jchar * w = env->GetStringChars(js, nullptr);
    std::string s = utf16_to_utf8(w, n);
    env->ReleaseStringChars(js, w);
    return s;
}

static void throw_err(JNIEnv * env, const std::string & msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

static void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n - 1, true);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_karen_ui_KarenLlama_nativeInit(JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    static bool backend_on = false;
    if (!backend_on) { llama_backend_init(); backend_on = true; }

    std::string path = jstr(env, jpath);
    Handle * h = new Handle();
    h->abort.store(false);
    h->n_ctx = n_ctx > 0 ? n_ctx : 2048;
    h->n_threads = n_threads > 0 ? n_threads : 4;
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.progress_callback = load_progress;
    mparams.progress_callback_user_data = (void *) &h->abort;
    llama_model * model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        bool cancelled = h->abort.load();
        delete h;
        throw_err(env, cancelled ? "cancelled" : "cannot load model file");
        return 0;
    }
    h->model = model;
    return (jlong) h;
}

JNIEXPORT jstring JNICALL
Java_com_karen_ui_KarenLlama_nativeComplete(JNIEnv * env, jobject, jlong ptr,
        jstring jsys, jobjectArray jroles, jobjectArray jtexts, jint max_tokens) {
    Handle * h = (Handle *) ptr;
    if (!h || !h->model) { throw_err(env, "model not loaded"); return nullptr; }
    h->abort.store(false);

    std::string sys = jstr(env, jsys);
    jsize turns = jroles ? env->GetArrayLength(jroles) : 0;
    jsize ntexts = jtexts ? env->GetArrayLength(jtexts) : 0;
    if (turns <= 0 || turns != ntexts) { throw_err(env, "empty prompt"); return nullptr; }

    std::vector<std::string> owned;
    owned.reserve((size_t) turns * 2 + 1);
    std::vector<llama_chat_message> msgs;
    msgs.reserve((size_t) turns + 1);
    owned.push_back(sys);
    msgs.push_back({ "system", owned.back().c_str() });
    for (jsize i = 0; i < turns; ++i) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jt = (jstring) env->GetObjectArrayElement(jtexts, i);
        owned.push_back(jstr(env, jr));
        owned.push_back(jstr(env, jt));
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jt);
        const char * role = owned[owned.size() - 2] == "assistant" ? "assistant" : "user";
        msgs.push_back({ role, owned.back().c_str() });
    }

    char tmpl[16384];
    int tlen = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true, tmpl, sizeof(tmpl));
    if (tlen < 0) { throw_err(env, "chat template failed"); return nullptr; }
    if (tlen >= (int) sizeof(tmpl)) { throw_err(env, "conversation too long, start a new chat"); return nullptr; }
    std::string prompt(tmpl, (size_t) tlen);

    const llama_vocab * vocab = llama_model_get_vocab(h->model);
    const int n_prompt = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (n_prompt <= 0) { throw_err(env, "cannot tokenize prompt"); return nullptr; }
    if (n_prompt + max_tokens > h->n_ctx) { throw_err(env, "prompt too long for context, start a new chat"); return nullptr; }
    std::vector<llama_token> ptoks((size_t) n_prompt);
    if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), ptoks.data(), n_prompt, true, true) < 0) {
        throw_err(env, "cannot tokenize prompt"); return nullptr;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = h->n_ctx;
    cparams.n_batch = 512;
    llama_context * ctx = llama_init_from_model(h->model, cparams);
    if (!ctx) { throw_err(env, "cannot create context"); return nullptr; }
    llama_set_abort_callback(ctx, abort_flag, (void *) &h->abort);
    llama_set_n_threads(ctx, h->n_threads, h->n_threads);

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    std::random_device rd;
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(rd()));

    llama_batch_ext * batch = llama_batch_ext_init(ctx);
    std::string out;
    bool failed = false;
    std::string fail_msg;

    int pos = 0;
    int remaining = n_prompt;
    while (remaining > 0 && !failed) {
        if (h->abort.load()) { failed = true; fail_msg = "cancelled"; break; }
        int chunk = remaining > 128 ? 128 : remaining;
        batch_set_tokens(batch, ptoks.data() + pos, chunk, pos);
        int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            if (h->abort.load()) break;
            failed = true; fail_msg = "prompt eval failed"; break;
        }
        pos += chunk;
        remaining -= chunk;
    }

    int made = 0;
    while (!failed && made < max_tokens) {
        if (h->abort.load()) break;
        llama_token id = llama_sampler_sample(smpl, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[256];
        int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (n < 0) { failed = true; fail_msg = "decode failed"; break; }
        out.append(piece, (size_t) n);
        batch_set_tokens(batch, &id, 1, pos);
        int rc = llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch);
        if (rc != 0) {
            if (h->abort.load()) break;
            failed = true; fail_msg = "decode failed"; break;
        }
        ++pos;
        ++made;
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(smpl);
    llama_free(ctx);

    if (failed && out.empty()) { throw_err(env, fail_msg); return nullptr; }
    std::vector<jchar> w = utf8_to_utf16(out);
    return env->NewString(w.data(), (jsize) w.size());
}

JNIEXPORT void JNICALL
Java_com_karen_ui_KarenLlama_nativeCancel(JNIEnv *, jobject, jlong ptr) {
    Handle * h = (Handle *) ptr;
    if (h) h->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_karen_ui_KarenLlama_nativeFree(JNIEnv *, jobject, jlong ptr) {
    Handle * h = (Handle *) ptr;
    if (!h) return;
    if (h->model) llama_model_free(h->model);
    delete h;
}

}
