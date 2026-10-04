#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <chrono>
#include <cctype>

#include "llama.h"
#include "ggml-backend.h"

#define TAG "LilacLocalAiJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Api {
    void * ll = nullptr;
    void * gg = nullptr;

    decltype(&llama_backend_init) llama_backend_init = nullptr;
    decltype(&llama_backend_free) llama_backend_free = nullptr;
    decltype(&llama_model_default_params) llama_model_default_params = nullptr;
    decltype(&llama_context_default_params) llama_context_default_params = nullptr;
    decltype(&llama_model_load_from_file) llama_model_load_from_file = nullptr;
    decltype(&llama_model_free) llama_model_free = nullptr;
    decltype(&llama_init_from_model) llama_init_from_model = nullptr;
    decltype(&llama_free) llama_free = nullptr;
    decltype(&llama_model_get_vocab) llama_model_get_vocab = nullptr;
    decltype(&llama_vocab_n_tokens) llama_vocab_n_tokens = nullptr;
    decltype(&llama_tokenize) llama_tokenize = nullptr;
    decltype(&llama_chat_apply_template) llama_chat_apply_template = nullptr;
    decltype(&llama_batch_init) llama_batch_init = nullptr;
    decltype(&llama_batch_free) llama_batch_free = nullptr;
    decltype(&llama_decode) llama_decode = nullptr;
    decltype(&llama_sampler_chain_default_params) llama_sampler_chain_default_params = nullptr;
    decltype(&llama_sampler_chain_init) llama_sampler_chain_init = nullptr;
    decltype(&llama_sampler_chain_add) llama_sampler_chain_add = nullptr;
    decltype(&llama_sampler_init_greedy) llama_sampler_init_greedy = nullptr;
    decltype(&llama_sampler_init_penalties) llama_sampler_init_penalties = nullptr;
    decltype(&llama_sampler_init_top_k) llama_sampler_init_top_k = nullptr;
    decltype(&llama_sampler_init_top_p) llama_sampler_init_top_p = nullptr;
    llama_sampler * (*llama_sampler_init_min_p)(float, size_t) = nullptr;
    llama_sampler * (*llama_sampler_init_typical)(float, size_t) = nullptr;
    decltype(&llama_sampler_init_temp) llama_sampler_init_temp = nullptr;
    decltype(&llama_sampler_init_dist) llama_sampler_init_dist = nullptr;
    decltype(&llama_sampler_free) llama_sampler_free = nullptr;
    decltype(&llama_sampler_sample) llama_sampler_sample = nullptr;
    decltype(&llama_vocab_is_eog) llama_vocab_is_eog = nullptr;
    decltype(&llama_token_to_piece) llama_token_to_piece = nullptr;
    decltype(&llama_get_memory) llama_get_memory = nullptr;
    decltype(&llama_memory_clear) llama_memory_clear = nullptr;
    decltype(&llama_model_ftype) llama_model_ftype = nullptr;
    decltype(&llama_ftype_name) llama_ftype_name = nullptr;

    decltype(&ggml_backend_dev_count) ggml_backend_dev_count = nullptr;
    decltype(&ggml_backend_dev_get) ggml_backend_dev_get = nullptr;
    decltype(&ggml_backend_dev_name) ggml_backend_dev_name = nullptr;
    decltype(&ggml_backend_dev_description) ggml_backend_dev_description = nullptr;
    decltype(&ggml_backend_dev_type) ggml_backend_dev_type = nullptr;
    decltype(&ggml_backend_dev_by_name) ggml_backend_dev_by_name = nullptr;
    decltype(&ggml_log_set) ggml_log_set = nullptr;
};

Api g;
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_ctx = nullptr;
std::string g_runtime_dir;
std::string g_backend = "auto";
std::string g_last_error;
std::string g_selected_backend_name;
bool g_backend_initialized = false;

void set_error(const std::string & s) {
    g_last_error = s;
    LOGE("%s", s.c_str());
}

template <typename T>
bool sym(void * handle, const char * name, T & out) {
    out = reinterpret_cast<T>(dlsym(handle, name));
    if (!out) {
        set_error(std::string("missing symbol: ") + name);
        return false;
    }
    return true;
}

bool load_api() {
    if (g.ll && g.gg) return true;
    const std::string ggml_path = g_runtime_dir + "/libggml.so";
    const std::string llama_path = g_runtime_dir + "/libllama.so";
    g.gg = dlopen(ggml_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g.gg) {
        set_error(std::string("dlopen libggml.so failed: ") + dlerror());
        return false;
    }
    g.ll = dlopen(llama_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!g.ll) {
        set_error(std::string("dlopen libllama.so failed: ") + dlerror());
        return false;
    }

#define LLAMA_SYM(x) if (!sym(g.ll, #x, g.x)) return false
    LLAMA_SYM(llama_backend_init);
    LLAMA_SYM(llama_backend_free);
    LLAMA_SYM(llama_model_default_params);
    LLAMA_SYM(llama_context_default_params);
    LLAMA_SYM(llama_model_load_from_file);
    LLAMA_SYM(llama_model_free);
    LLAMA_SYM(llama_init_from_model);
    LLAMA_SYM(llama_free);
    LLAMA_SYM(llama_model_get_vocab);
    LLAMA_SYM(llama_vocab_n_tokens);
    LLAMA_SYM(llama_tokenize);
    LLAMA_SYM(llama_chat_apply_template);
    LLAMA_SYM(llama_batch_init);
    LLAMA_SYM(llama_batch_free);
    LLAMA_SYM(llama_decode);
    LLAMA_SYM(llama_sampler_chain_default_params);
    LLAMA_SYM(llama_sampler_chain_init);
    LLAMA_SYM(llama_sampler_chain_add);
    LLAMA_SYM(llama_sampler_init_greedy);
    LLAMA_SYM(llama_sampler_init_penalties);
    LLAMA_SYM(llama_sampler_init_top_k);
    LLAMA_SYM(llama_sampler_init_top_p);
    g.llama_sampler_init_min_p = reinterpret_cast<decltype(g.llama_sampler_init_min_p)>(dlsym(g.ll, "llama_sampler_init_min_p"));
    g.llama_sampler_init_typical = reinterpret_cast<decltype(g.llama_sampler_init_typical)>(dlsym(g.ll, "llama_sampler_init_typical"));
    LLAMA_SYM(llama_sampler_init_temp);
    LLAMA_SYM(llama_sampler_init_dist);
    LLAMA_SYM(llama_sampler_free);
    LLAMA_SYM(llama_sampler_sample);
    LLAMA_SYM(llama_vocab_is_eog);
    LLAMA_SYM(llama_token_to_piece);
    LLAMA_SYM(llama_get_memory);
    LLAMA_SYM(llama_memory_clear);
    LLAMA_SYM(llama_model_ftype);
    LLAMA_SYM(llama_ftype_name);
#undef LLAMA_SYM

#define GGML_SYM(x) if (!sym(g.gg, #x, g.x)) return false
    GGML_SYM(ggml_backend_dev_count);
    GGML_SYM(ggml_backend_dev_get);
    GGML_SYM(ggml_backend_dev_name);
    GGML_SYM(ggml_backend_dev_description);
    GGML_SYM(ggml_backend_dev_type);
    GGML_SYM(ggml_backend_dev_by_name);
    GGML_SYM(ggml_log_set);
#undef GGML_SYM
    return true;
}

void ggml_android_log_callback(enum ggml_log_level level, const char * text, void *) {
    // Suppress GGML debug/trace output. It can emit thousands of lines per
    // translation and materially distorts timing on Android. Keep warnings
    // and errors visible; normal JNI timing/backend logs are emitted below.
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    if (!text) return;
    const char * prefix = level == GGML_LOG_LEVEL_ERROR ? "E"
        : level == GGML_LOG_LEVEL_WARN ? "W"
        : level == GGML_LOG_LEVEL_INFO ? "I" : "D";
    __android_log_print(ANDROID_LOG_INFO, TAG, "GGML[%s] %s", prefix, text);
}

void install_ggml_log_callback() {
    if (g.ggml_log_set) {
        g.ggml_log_set(ggml_android_log_callback, nullptr);
        LOGI("GGML_LOG_CALLBACK_INSTALLED");
    }
}

void log_devices() {
    const char * ld = std::getenv("LD_LIBRARY_PATH");
    const char * adsp = std::getenv("ADSP_LIBRARY_PATH");
    const char * hexdev = std::getenv("GGML_HEXAGON_DEVICES");
    const char * hexverbose = std::getenv("GGML_HEXAGON_VERBOSE");
    LOGI("ENV LD_LIBRARY_PATH=%s", ld ? ld : "<unset>");
    LOGI("ENV ADSP_LIBRARY_PATH=%s", adsp ? adsp : "<unset>");
    LOGI("ENV GGML_HEXAGON_DEVICES=%s", hexdev ? hexdev : "<unset>");
    LOGI("ENV GGML_HEXAGON_VERBOSE=%s", hexverbose ? hexverbose : "<unset>");

    const size_t n = g.ggml_backend_dev_count();
    LOGI("DEVICE_COUNT=%zu", n);
    bool has_htp = false;
    bool has_gpu = false;
    bool has_cpu = false;
    for (size_t i = 0; i < n; ++i) {
        auto * d = g.ggml_backend_dev_get(i);
        if (!d) continue;
        const std::string name = g.ggml_backend_dev_name(d);
        const int type = (int) g.ggml_backend_dev_type(d);
        LOGI("DEVICE[%zu] name=%s type=%d desc=%s", i,
             name.c_str(), type, g.ggml_backend_dev_description(d));
        if (name.rfind("HTP", 0) == 0) has_htp = true;
        if (name.find("GPU") != std::string::npos || name.find("OpenCL") != std::string::npos) has_gpu = true;
        if (type == GGML_BACKEND_DEVICE_TYPE_CPU) has_cpu = true;
    }
    LOGI("DEVICE_CAPS HTP=%s GPU=%s CPU=%s",
         has_htp ? "YES" : "NO", has_gpu ? "YES" : "NO", has_cpu ? "YES" : "NO");
    if (!has_htp) {
        LOGW("HTP_DEVICE_NOT_ENUMERATED; NPU backend is not available to this process yet");
    }
}

ggml_backend_dev_t find_device(const char * wanted) {
    if (!wanted || !*wanted) return nullptr;
    return g.ggml_backend_dev_by_name(wanted);
}

bool load_model_once(const char * model_path, int context_size, int threads, ggml_backend_dev_t requested) {
    if (g_ctx) { g.llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { g.llama_model_free(g_model); g_model = nullptr; }

    llama_model_params mp = g.llama_model_default_params();
    mp.n_gpu_layers = -1;
    mp.split_mode = LLAMA_SPLIT_MODE_LAYER;

    ggml_backend_dev_t selected = requested;
    if (!selected) {
        set_error("requested backend device is unavailable");
        return false;
    }

    const char * selected_name = g.ggml_backend_dev_name(selected);
    g_selected_backend_name = selected_name ? selected_name : "unknown";
    ggml_backend_dev_t devices[2] = { selected, nullptr };
    mp.devices = devices;
    LOGI("BACKEND_SELECTED name=%s requested=%s", g_selected_backend_name.c_str(), g_backend.c_str());

    const bool using_htp = g_selected_backend_name.rfind("HTP", 0) == 0;
    llama_context_params cp = g.llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(std::max(1024, context_size));
    cp.n_batch = std::min<uint32_t>(1024u, cp.n_ctx);
    cp.n_ubatch = using_htp ? std::min<uint32_t>(31u, cp.n_batch)
                            : std::min<uint32_t>(512u, cp.n_batch);
    LOGI("CONTEXT_CONFIG n_ctx=%u n_batch=%u n_ubatch=%u backend=%s", cp.n_ctx, cp.n_batch, cp.n_ubatch, g_selected_backend_name.c_str());
    cp.n_threads = threads > 0 ? threads : 6;
    cp.n_threads_batch = cp.n_threads;
    cp.offload_kqv = true;
    cp.op_offload = true;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.no_perf = false;

    LOGI("MODEL_LOAD_START backend=%s path=%s", g_selected_backend_name.c_str(), model_path ? model_path : "<null>");
    const auto model_load_start = std::chrono::steady_clock::now();
    g_model = g.llama_model_load_from_file(model_path, mp);
    const auto model_load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - model_load_start).count();
    if (!g_model) {
        set_error(std::string("llama_model_load_from_file failed on backend=") + g_selected_backend_name);
        return false;
    }

    const char * ft = g.llama_ftype_name(g.llama_model_ftype(g_model));
    LOGI("MODEL_LOADED backend=%s ftype=%s model_load_ms=%lld", g_selected_backend_name.c_str(), ft ? ft : "?", (long long)model_load_ms);

    g_ctx = g.llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        set_error(std::string("llama_init_from_model failed on backend=") + g_selected_backend_name);
        g.llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }
    LOGI("CONTEXT_READY backend=%s", g_selected_backend_name.c_str());
    return true;
}

ggml_backend_dev_t find_first_device_matching(const std::string & kind) {
    const size_t n = g.ggml_backend_dev_count();
    for (size_t i = 0; i < n; ++i) {
        auto * d = g.ggml_backend_dev_get(i);
        if (!d) continue;
        const std::string name = g.ggml_backend_dev_name(d);
        const int type = (int) g.ggml_backend_dev_type(d);
        if (kind == "npu" && name.rfind("HTP", 0) == 0) return d;
        if (kind == "gpu" && (name.find("GPU") != std::string::npos || name.find("OpenCL") != std::string::npos)) return d;
        if (kind == "cpu" && type == GGML_BACKEND_DEVICE_TYPE_CPU) return d;
            }
    return nullptr;
}

bool load_model(const char * model_path, int context_size, int threads) {
    LOGI("BACKEND_REQUESTED mode=%s", g_backend.c_str());

    std::vector<std::string> order;
    if (g_backend == "auto") {
        order = {"npu", "gpu", "cpu"};
    } else {
        size_t start = 0;
        while (start < g_backend.size()) {
            const size_t comma = g_backend.find(',', start);
            const size_t end = comma == std::string::npos ? g_backend.size() : comma;
            std::string item = g_backend.substr(start, end - start);
            item.erase(std::remove_if(item.begin(), item.end(), [](unsigned char c) { return std::isspace(c); }), item.end());
            if (!item.empty()) order.push_back(item);
            if (comma == std::string::npos) break;
            start = comma + 1;
        }
        if (order.empty()) order = {"npu", "gpu", "cpu"};
    }

    for (const std::string & kind : order) {
        ggml_backend_dev_t d = find_first_device_matching(kind);
        if (!d) {
            LOGW("BACKEND_UNAVAILABLE kind=%s", kind.c_str());
            if (g_backend != "auto" && order.size() == 1) {
                set_error("requested backend is unavailable: " + kind);
                return false;
            }
            continue;
        }

        const char * name = g.ggml_backend_dev_name(d);
        LOGI("BACKEND_ATTEMPT kind=%s name=%s", kind.c_str(), name ? name : "?");
        if (load_model_once(model_path, context_size, threads, d)) {
            LOGI("BACKEND_ACTIVE kind=%s name=%s", kind.c_str(), name ? name : "?");
            return true;
        }
        LOGW("BACKEND_ATTEMPT_FAILED kind=%s name=%s error=%s", kind.c_str(), name ? name : "?", g_last_error.c_str());
    }

    set_error("all requested/automatic backends failed");
    return false;
}

std::string token_piece(llama_token token, llama_vocab * vocab) {
    std::vector<char> buf(256);
    int n = g.llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = g.llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, false);
    }
    if (n <= 0) return {};
    return std::string(buf.data(), static_cast<size_t>(n));
}

struct FormattedPrompt {
    std::string text;
    bool add_special = true;
};

// LilacAnime is a subtitle translation runtime. Reasoning is intentionally
// disabled at the native boundary for every model. The Kotlin/UI setting is
// still accepted for source compatibility, but it cannot re-enable reasoning.
static constexpr bool FORCE_REASONING_OFF = true;

FormattedPrompt apply_chat_template(const std::vector<std::string> & roles, const std::vector<std::string> & contents, const std::string & chat_template, bool add_generation_prompt, int thinking_mode) {
    const int effective_thinking_mode = FORCE_REASONING_OFF ? 2 : thinking_mode;
    if (roles.size() != contents.size() || roles.empty()) {
        set_error("invalid chat messages");
        return {};
    }

    if (chat_template.empty()) {
        return {contents.back(), true};
    }

    // Gemma 4 introduced a new <|turn> / <turn|> template. Some llama.cpp
    // C-API builds can load Gemma 4 successfully but still return -1 from
    // llama_chat_apply_template() because the legacy template detector does
    // not recognize the new format. The GGUF template itself contains this
    // marker, so use a small model-agnostic fallback based on the template
    // syntax rather than hard-coding a model name into the runtime.
    if (chat_template.find("<|turn>") != std::string::npos &&
        chat_template.find("<turn|>") != std::string::npos) {
        std::string result;
        for (size_t i = 0; i < roles.size(); ++i) {
            std::string role = roles[i] == "assistant" ? "model" : roles[i];
            result += "<|turn>" + role + "\n";
            result += contents[i];
            result += "<turn|>\n";
        }
        if (add_generation_prompt) {
            // For Gemma 4 E4B, forced reasoning-off means the generation
            // boundary ends directly at the model turn. Do not emit a
            // thought/channel marker because that can re-enter reasoning.
            result += "<|turn>model\n";
        }
        LOGI("GEMMA4_TEMPLATE_FALLBACK requested_thinking=%d effective_thinking=off chars=%zu", thinking_mode, result.size());
        return {result, true};
    }

    // For templates that expose an enable_thinking Jinja variable (for
    // example Qwen-family templates), force that variable off before the
    // llama.cpp template renderer sees it. This is deliberately done in the
    // native runtime as a final safety net rather than relying on the UI.
    std::string effective_template = chat_template;
    if (FORCE_REASONING_OFF && effective_template.find("enable_thinking") != std::string::npos) {
        effective_template = "{%- set enable_thinking = false %}\n" + effective_template;
        const std::string true_assignment = "enable_thinking = true";
        size_t pos = 0;
        while ((pos = effective_template.find(true_assignment, pos)) != std::string::npos) {
            effective_template.replace(pos, true_assignment.size(), "enable_thinking = false");
            pos += 24;
        }
        LOGI("REASONING_FORCE_OFF template_variable=enable_thinking");
    }

    std::vector<llama_chat_message> messages;
    messages.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        messages.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    int32_t capacity = 4096;
    for (;;) {
        std::vector<char> buffer(static_cast<size_t>(capacity));
        const int32_t result = g.llama_chat_apply_template(
            effective_template.empty() ? nullptr : effective_template.c_str(),
            messages.data(),
            messages.size(),
            add_generation_prompt,
            buffer.data(),
            capacity
        );
        if (result < 0) {
            set_error("chat template application failed");
            return {};
        }
        if (result < capacity) {
            return {std::string(buffer.data(), static_cast<size_t>(result)), true};
        }
        capacity = result + 1;
        if (capacity > 262144) {
            set_error("formatted chat prompt is too large");
            return {};
        }
    }
}

std::string translate(const char * prompt, int max_tokens, bool add_special, float temperature, float top_p, int top_k, float repetition_penalty, int repeat_last_n, float min_p, float typical_p, float frequency_penalty, float presence_penalty, int seed) {
    using steady_clock = std::chrono::steady_clock;
    const auto total_start = steady_clock::now();
    if (!g_model || !g_ctx) return {};

    g.llama_memory_clear(g.llama_get_memory(g_ctx), true);
    const llama_vocab * vocab = g.llama_model_get_vocab(g_model);
    if (!vocab) return {};

    const int32_t prompt_len = static_cast<int32_t>(std::strlen(prompt));
    LOGI("TOKENIZE_BEGIN chars=%d addSpecial=%s parseSpecial=true", prompt_len, add_special ? "true" : "false");

    int32_t n_tokens = g.llama_tokenize(
        vocab, prompt, prompt_len, nullptr, 0, add_special, true
    );
    if (n_tokens == INT32_MIN) {
        set_error("tokenization size overflow");
        return {};
    }
    if (n_tokens >= 0) {
        LOGW("TOKENIZE_PROBE_NONNEGATIVE tokens=%d", n_tokens);
    } else {
        n_tokens = -n_tokens;
    }
    if (n_tokens <= 0) {
        set_error("tokenization returned no tokens");
        return {};
    }

    std::vector<llama_token> tokens(static_cast<size_t>(n_tokens));
    const int32_t tokenized = g.llama_tokenize(
        vocab, prompt, prompt_len, tokens.data(), n_tokens, add_special, true
    );
    LOGI("TOKENIZE_DONE required=%d actual=%d", n_tokens, tokenized);
    if (tokenized < 0 || tokenized != n_tokens) {
        set_error("tokenization failed");
        return {};
    }

    // IMPORTANT: only the final prompt token receives logits.  The sampler
    // therefore sees the distribution for the NEXT token after the fully
    // formatted prompt.  No prompt token is ever sampled as output.
    LOGI("GENERATION_BOUNDARY prompt_tokens=%d next_token_only=true", n_tokens);

    llama_batch batch = g.llama_batch_init(std::max<int32_t>(n_tokens, 1), 0, 1);
    batch.n_tokens = n_tokens;
    for (int32_t i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[static_cast<size_t>(i)];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;
    }
    batch.logits[n_tokens - 1] = 1;

    const auto prompt_decode_start = steady_clock::now();
    if (g.llama_decode(g_ctx, batch) != 0) {
        g.llama_batch_free(batch);
        set_error("prompt decode failed");
        return {};
    }
    const auto prompt_decode_end = steady_clock::now();

    auto sampler_params = g.llama_sampler_chain_default_params();
    llama_sampler * sampler = g.llama_sampler_chain_init(sampler_params);
    if (!sampler) {
        g.llama_batch_free(batch);
        set_error("sampler init failed");
        return {};
    }
    g.llama_sampler_chain_add(
        sampler,
        g.llama_sampler_init_penalties(
            g.llama_vocab_n_tokens(vocab),
            std::max(0, repeat_last_n),
            repetition_penalty,
            frequency_penalty,
            presence_penalty
        )
    );
    g.llama_sampler_chain_add(sampler, g.llama_sampler_init_top_k(top_k));
    if (typical_p < 1.0f && g.llama_sampler_init_typical) {
        g.llama_sampler_chain_add(sampler, g.llama_sampler_init_typical(typical_p, 1));
    }
    g.llama_sampler_chain_add(sampler, g.llama_sampler_init_top_p(top_p, 1));
    if (min_p > 0.0f && g.llama_sampler_init_min_p) {
        g.llama_sampler_chain_add(sampler, g.llama_sampler_init_min_p(min_p, 1));
    }
    g.llama_sampler_chain_add(sampler, g.llama_sampler_init_temp(temperature));
    LOGI("SAMPLER_CONFIG temperature=%.3f top_p=%.3f top_k=%d min_p=%.3f typical_p=%.3f repeat_last_n=%d repetition_penalty=%.3f frequency_penalty=%.3f presence_penalty=%.3f seed=%d", temperature, top_p, top_k, min_p, typical_p, repeat_last_n, repetition_penalty, frequency_penalty, presence_penalty, seed);
    g.llama_sampler_chain_add(sampler, g.llama_sampler_init_dist(seed));

    std::string output;
    output.reserve(1024);
    const int limit = std::max(1, max_tokens);
    int generated_tokens = 0;
    const auto generation_start = steady_clock::now();
    for (int i = 0; i < limit; ++i) {
        llama_token token = g.llama_sampler_sample(sampler, g_ctx, -1);
        if (g.llama_vocab_is_eog(vocab, token)) break;
        output += token_piece(token, const_cast<llama_vocab *>(vocab));
        ++generated_tokens;

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = n_tokens + i;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (g.llama_decode(g_ctx, batch) != 0) break;
    }

    const auto generation_end = steady_clock::now();
    g.llama_sampler_free(sampler);
    g.llama_batch_free(batch);

    const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prompt_decode_end - prompt_decode_start).count();
    const auto generation_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_end - generation_start).count();
    const auto total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(generation_end - total_start).count();
    const double tokens_per_sec = generation_ms > 0
        ? (static_cast<double>(generated_tokens) * 1000.0 / static_cast<double>(generation_ms))
        : 0.0;

    LOGI("TRANSLATE_DONE backend=%s prompt_tokens=%d generated_tokens=%d output_chars=%zu prompt_ms=%lld generation_ms=%lld total_ms=%lld tokens_per_sec=%.2f",
         g_selected_backend_name.c_str(), n_tokens, generated_tokens, output.size(),
         (long long)prompt_ms, (long long)generation_ms, (long long)total_ms, tokens_per_sec);
    return output;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeInit(
        JNIEnv * env, jclass, jstring runtime_dir, jstring backend) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const char * dir = runtime_dir ? env->GetStringUTFChars(runtime_dir, nullptr) : nullptr;
    const char * be = backend ? env->GetStringUTFChars(backend, nullptr) : nullptr;
    g_runtime_dir = dir ? dir : "";
    g_backend = be ? be : "auto";
    if (dir) env->ReleaseStringUTFChars(runtime_dir, dir);
    if (be) env->ReleaseStringUTFChars(backend, be);

    if (!g_runtime_dir.empty()) {
        const char * probe_libs[] = {
            "libcdsprpc.so",
            "vendor.qti.hardware.dsp@1.0.so",
            "vendor.qti.hardware.dsp-V1-ndk.so",
            "libvmmem.so"
        };
        for (const char * name : probe_libs) {
            void * h = dlopen(name, RTLD_NOW | RTLD_LOCAL);
            if (h) {
                LOGI("SYSTEM_NATIVE_LIBRARY_OK name=%s", name);
                dlclose(h);
            } else {
                const char * err = dlerror();
                LOGW("SYSTEM_NATIVE_LIBRARY_FAIL name=%s err=%s", name, err ? err : "unknown");
            }
        }

        // Keep LD_LIBRARY_PATH limited to the app runtime. In particular, do
        // not append /vendor/lib64: recent Snapdragon devices can lose the
        // Hexagon/FastRPC HAL fallback when that directory is injected here.
        // ADSP_LIBRARY_PATH intentionally points at the bundled HTP kernels.
        setenv("LD_LIBRARY_PATH", g_runtime_dir.c_str(), 1);
        setenv("ADSP_LIBRARY_PATH", g_runtime_dir.c_str(), 1);
        setenv("GGML_HEXAGON_DEVICES", "HTP0:0", 1);
        setenv("GGML_HEXAGON_VERBOSE", "0", 1);
        setenv("GGML_HEXAGON_EXPERIMENTAL", "1", 1);
    }

    if (!load_api()) return JNI_FALSE;
    if (!g_backend_initialized) {
        g.llama_backend_init();
        g_backend_initialized = true;
    }
    install_ggml_log_callback();
    log_devices();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeLoad(
        JNIEnv * env, jclass, jstring model_path, jint context_size, jint threads) {
    std::lock_guard<std::mutex> guard(g_mutex);
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    const bool ok = path && load_model(path, context_size, threads);
    if (path) env->ReleaseStringUTFChars(model_path, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}


// The JNI layer remains model-agnostic. Model-family adapters may prepare the
// prompt, but the translation runtime enforces reasoning OFF as a final safety
// boundary because subtitle translation does not need hidden chain-of-thought.

extern "C" JNIEXPORT jstring JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeTranslateAdvanced(
        JNIEnv * env, jclass, jobjectArray roles, jobjectArray contents, jstring chat_template, jboolean use_chat_template, jboolean add_generation_prompt, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat repetition_penalty, jint repeat_last_n, jfloat min_p, jfloat typical_p, jfloat frequency_penalty, jfloat presence_penalty, jint seed, jint thinking_mode) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (!roles || !contents || !chat_template) return env->NewStringUTF("");

    const jsize role_count = env->GetArrayLength(roles);
    const jsize content_count = env->GetArrayLength(contents);
    if (role_count <= 0 || role_count != content_count) return env->NewStringUTF("");

    const char * tmpl = env->GetStringUTFChars(chat_template, nullptr);
    if (!tmpl) return env->NewStringUTF("");

    std::vector<std::string> role_strings;
    std::vector<std::string> content_strings;
    role_strings.reserve(static_cast<size_t>(role_count));
    content_strings.reserve(static_cast<size_t>(content_count));

    for (jsize i = 0; i < role_count; ++i) {
        auto role_obj = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content_obj = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        const char * role = role_obj ? env->GetStringUTFChars(role_obj, nullptr) : nullptr;
        const char * content = content_obj ? env->GetStringUTFChars(content_obj, nullptr) : nullptr;
        role_strings.emplace_back(role ? role : "");
        content_strings.emplace_back(content ? content : "");
        if (role) env->ReleaseStringUTFChars(role_obj, role);
        if (content) env->ReleaseStringUTFChars(content_obj, content);
        if (role_obj) env->DeleteLocalRef(role_obj);
        if (content_obj) env->DeleteLocalRef(content_obj);
    }

    const int effectiveThinkingMode = FORCE_REASONING_OFF ? 2 : static_cast<int>(thinking_mode);
    LOGI("REASONING_FORCE_OFF requested_mode=%d effective_mode=off", static_cast<int>(thinking_mode));
    const std::string effectiveTemplate = std::string(tmpl);

    FormattedPrompt formatted;
    if (use_chat_template == JNI_TRUE) {
        formatted = apply_chat_template(
            role_strings,
            content_strings,
            effectiveTemplate,
            add_generation_prompt == JNI_TRUE,
            effectiveThinkingMode
        );
    } else {
        formatted.text = content_strings.back();
        formatted.add_special = true;
        LOGI("PROMPT_MODE_COMPLETION raw_chars=%zu", formatted.text.size());
    }
    env->ReleaseStringUTFChars(chat_template, tmpl);
    if (formatted.text.empty()) return env->NewStringUTF("");

    LOGI("PROMPT_FORMAT mode=%s messages=%d add_generation_prompt=%s chars=%zu addSpecial=%s",
         use_chat_template == JNI_TRUE ? "chat" : "completion",
         static_cast<int>(role_count),
         add_generation_prompt == JNI_TRUE ? "true" : "false",
         formatted.text.size(),
         formatted.add_special ? "true" : "false");

    const std::string result = translate(formatted.text.c_str(), max_tokens, formatted.add_special, temperature, top_p, top_k, repetition_penalty, repeat_last_n, min_p, typical_p, frequency_penalty, presence_penalty, seed);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeTranslate(
        JNIEnv * env, jclass, jobjectArray roles, jobjectArray contents, jstring chat_template, jboolean add_generation_prompt, jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat repetition_penalty) {
    return Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeTranslateAdvanced(
        env, nullptr, roles, contents, chat_template, JNI_TRUE, add_generation_prompt, max_tokens,
        temperature, top_p, top_k, repetition_penalty, 64, 0.0f, 1.0f, 0.0f, 0.0f, 1234, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeRelease(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_ctx) { g.llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { g.llama_model_free(g_model); g_model = nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lilac_anime_data_subtitle_translation_localai_LocalAiNative_nativeShutdown(
        JNIEnv *, jclass) {
    std::lock_guard<std::mutex> guard(g_mutex);
    if (g_ctx) { g.llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { g.llama_model_free(g_model); g_model = nullptr; }
    if (g_backend_initialized && g.llama_backend_free) g.llama_backend_free();
    g_backend_initialized = false;
}
