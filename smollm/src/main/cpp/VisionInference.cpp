#include "VisionInference.h"
#include <android/log.h>
#include <cstring>
#include <iostream>

#define TAG "[VisionInference-Cpp]"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * Convert a llama token to its string representation.
 * Uses llama_token_to_piece directly to avoid dependency on the 'common' library.
 */
static std::string token_to_piece(const llama_vocab* vocab, llama_token token, bool special = false) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, special);
    if (n < 0) {
        return "";
    }
    return std::string(buf, n);
}

VisionInference::VisionInference() {
    // nothing to initialize here
}

VisionInference::~VisionInference() {
    unloadModel();
}

bool VisionInference::loadModel(const char* modelPath, const char* mmprojPath, int nThreads) {
    LOGi("Loading vision model:\n\tmodel_path = %s\n\tmmproj_path = %s\n\tn_threads = %d",
         modelPath, mmprojPath, nThreads);

    _nThreads = nThreads;

    // Load dynamic backends
    ggml_backend_load_all();

    // Load the text model
    llama_model_params modelParams = llama_model_default_params();
    modelParams.use_mmap = true;
    modelParams.use_mlock = false;

    _model = llama_model_load_from_file(modelPath, modelParams);
    if (!_model) {
        LOGe("Failed to load model from %s", modelPath);
        return false;
    }

    // Create context
    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = 4096;
    ctxParams.n_batch = 4096;
    ctxParams.n_threads = _nThreads;
    ctxParams.n_threads_batch = _nThreads;
    ctxParams.no_perf = true;

    _ctx = llama_init_from_model(_model, ctxParams);
    if (!_ctx) {
        LOGe("llama_init_from_model() returned null");
        llama_model_free(_model);
        _model = nullptr;
        return false;
    }

    _vocab = const_cast<llama_vocab*>(llama_model_get_vocab(_model));

    // Create sampler
    llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = true;
    _sampler = llama_sampler_chain_init(samplerParams);
    llama_sampler_chain_add(_sampler, llama_sampler_init_greedy());

    // Load vision projector (mmproj)
    mtmd_context_params mtmdParams = mtmd_context_params_default();
    mtmdParams.use_gpu = false; // CPU-only for now; can be changed later
    mtmdParams.print_timings = false;
    mtmdParams.n_threads = _nThreads;
    mtmdParams.warmup = true;

    _mtmdCtx = mtmd_init_from_file(mmprojPath, _model, mtmdParams);
    if (!_mtmdCtx) {
        LOGe("Failed to load vision projector from %s", mmprojPath);
        llama_free(_ctx);
        _ctx = nullptr;
        llama_model_free(_model);
        _model = nullptr;
        llama_sampler_free(_sampler);
        _sampler = nullptr;
        return false;
    }

    LOGi("Vision model loaded successfully. mtmd_support_vision = %d",
         mtmd_support_vision(_mtmdCtx));
    return true;
}

bool VisionInference::isModelLoaded() const {
    return _model != nullptr && _mtmdCtx != nullptr;
}

void VisionInference::unloadModel() {
    stopCompletion();
    clearImages();

    if (_mtmdCtx) {
        mtmd_free(_mtmdCtx);
        _mtmdCtx = nullptr;
    }
    if (_sampler) {
        llama_sampler_free(_sampler);
        _sampler = nullptr;
    }
    if (_ctx) {
        llama_free(_ctx);
        _ctx = nullptr;
    }
    if (_model) {
        llama_model_free(_model);
        _model = nullptr;
    }
    _vocab = nullptr;
}

bool VisionInference::loadImageFromBuffer(const unsigned char* buffer, size_t len) {
    if (!_mtmdCtx) {
        LOGe("Cannot load image: mtmd context is not initialized");
        return false;
    }

    mtmd_bitmap* bmp = mtmd_helper_bitmap_init_from_buf(_mtmdCtx, buffer, len);
    if (!bmp) {
        LOGe("Failed to load image from buffer (len=%zu)", len);
        return false;
    }

    _bitmaps.emplace_back(bmp);
    LOGi("Image loaded: %dx%d", mtmd_bitmap_get_nx(bmp), mtmd_bitmap_get_ny(bmp));
    return true;
}

void VisionInference::clearImages() {
    _bitmaps.clear();
}

void VisionInference::resetGenerationState() {
    _response.clear();
    _cacheResponseTokens.clear();
    _responseGenerationTime = 0;
    _responseNumTokens = 0;
    _nPast = 0;
    _currToken = 0;
}

bool VisionInference::startCompletion(const char* prompt) {
    if (!_model || !_ctx || !_mtmdCtx) {
        LOGe("Model not loaded");
        return false;
    }

    resetGenerationState();

    // Clear memory so each vision query is independent
    llama_memory_clear(llama_get_memory(_ctx), false);

    // Build the prompt with media markers if not present
    std::string fullPrompt = prompt;
    if (fullPrompt.find(mtmd_default_marker()) == std::string::npos && !_bitmaps.empty()) {
        // Prepend marker for each image
        std::string markers;
        for (size_t i = 0; i < _bitmaps.size(); i++) {
            markers += mtmd_default_marker();
        }
        fullPrompt = markers + fullPrompt;
    }

    LOGi("Prompt: %s", fullPrompt.c_str());

    // Prepare text input
    mtmd_input_text text;
    text.text = fullPrompt.c_str();
    text.add_special = true;
    text.parse_special = true;

    // Build C-compatible pointer array from loaded bitmaps
    std::vector<const mtmd_bitmap*> bitmapsCptr;
    bitmapsCptr.reserve(_bitmaps.size());
    for (auto& bmp : _bitmaps) {
        bitmapsCptr.push_back(bmp.ptr.get());
    }

    // Tokenize text + images into chunks
    mtmd::input_chunks chunks(mtmd_input_chunks_init());
    int32_t tokenizeRes = mtmd_tokenize(
        _mtmdCtx,
        chunks.ptr.get(),
        &text,
        bitmapsCptr.data(),
        bitmapsCptr.size()
    );

    if (tokenizeRes != 0) {
        LOGe("mtmd_tokenize failed with code %d", tokenizeRes);
        return false;
    }

    LOGi("Tokenized into %zu chunks", chunks.size());

    // Evaluate all chunks (text + images)
    llama_pos newNPast = 0;
    int32_t evalRes = mtmd_helper_eval_chunks(
        _mtmdCtx,
        _ctx,
        chunks.ptr.get(),
        _nPast,
        0,      // seq_id
        512,    // n_batch
        true,   // logits_last
        &newNPast
    );

    if (evalRes != 0) {
        LOGe("mtmd_helper_eval_chunks failed with code %d", evalRes);
        return false;
    }

    _nPast = newNPast;
    _nCtxUsed = static_cast<int>(_nPast);

    LOGi("Chunks evaluated. n_past = %d", (int)_nPast);
    return true;
}

std::string VisionInference::completionLoop() {
    if (!_ctx || !_sampler || !_vocab) {
        return "[EOG]";
    }

    uint32_t contextSize = llama_n_ctx(_ctx);
    if (_nCtxUsed + 1 > contextSize) {
        LOGe("Context size reached");
        return "[EOG]";
    }

    auto start = ggml_time_us();

    // Sample next token
    _currToken = llama_sampler_sample(_sampler, _ctx, -1);

    if (llama_vocab_is_eog(_vocab, _currToken)) {
        return "[EOG]";
    }

    std::string piece = token_to_piece(_vocab, _currToken, true);
    auto end = ggml_time_us();

    _responseGenerationTime += (end - start);
    _responseNumTokens += 1;
    _cacheResponseTokens += piece;

    // Decode the sampled token for next iteration
    llama_batch batch = llama_batch_get_one(&_currToken, 1);
    if (llama_decode(_ctx, batch) != 0) {
        LOGe("llama_decode() failed");
        return "[EOG]";
    }

    _nPast += 1;
    _nCtxUsed = static_cast<int>(_nPast);

    // Return complete UTF-8 chunks
    if (_isValidUtf8(_cacheResponseTokens.c_str())) {
        std::string validPiece = _cacheResponseTokens;
        _cacheResponseTokens.clear();
        _response += validPiece;
        return validPiece;
    }

    return "";
}

void VisionInference::stopCompletion() {
    _response.clear();
    _cacheResponseTokens.clear();
}

float VisionInference::getResponseGenerationTime() const {
    if (_responseGenerationTime == 0) return 0.0f;
    return (float)_responseNumTokens / (_responseGenerationTime / 1e6);
}

int VisionInference::getContextSizeUsed() const {
    return _nCtxUsed;
}

std::string VisionInference::getModelDescription() const {
    if (!_model) return "no model loaded";
    char desc[128];
    llama_model_desc(_model, desc, sizeof(desc));
    return std::string(desc);
}

bool VisionInference::_isValidUtf8(const char* response) {
    if (!response) return true;
    const unsigned char* bytes = (const unsigned char*)response;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}
