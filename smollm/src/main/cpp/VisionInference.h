#pragma once

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <string>
#include <vector>
#include <memory>

/**
 * Wrapper around llama.cpp's libmtmd for multimodal (vision) inference.
 * 
 * This class manages:
 *  - Loading a text model + mmproj (vision projector)
 *  - Accepting images as raw JPEG buffers
 *  - Running tokenization with mixed text + image chunks
 *  - Generating text responses from vision prompts
 */
class VisionInference {
public:
    VisionInference();
    ~VisionInference();

    /**
     * Load both the text model and the vision projector (mmproj).
     * 
     * @param modelPath  Path to the text GGUF model (e.g. qwen3.5-4b-instruct-q4_k_m.gguf)
     * @param mmprojPath Path to the vision projector GGUF (e.g. qwen3.5-4b-instruct-mmproj-f16.gguf)
     * @param nThreads   Number of CPU threads for inference
     * @return true on success, false on failure
     */
    bool loadModel(const char* modelPath, const char* mmprojPath, int nThreads);

    /**
     * Check if a model is currently loaded.
     */
    bool isModelLoaded() const;

    /**
     * Load an image from a raw JPEG/PNG buffer into memory.
     * The image is parsed by stb_image (via mtmd_helper_bitmap_init_from_buf).
     * 
     * @param buffer Raw image bytes (JPEG, PNG, BMP, etc.)
     * @param len    Length of the buffer in bytes
     * @return true on success, false on failure
     */
    bool loadImageFromBuffer(const unsigned char* buffer, size_t len);

    /**
     * Clear any previously loaded images.
     */
    void clearImages();

    /**
     * Start a vision completion with the given prompt.
     * The prompt should contain the media marker <__media__> where the image(s) should be inserted.
     * If no marker is present, images are prepended automatically.
     * 
     * @param prompt The text prompt (e.g. "Describe this image: <__media__>")
     * @return true on success, false on failure
     */
    bool startCompletion(const char* prompt);

    /**
     * Generate the next token in the completion.
     * Call this in a loop until it returns "[EOG]" (end of generation).
     * 
     * @return A piece of the generated text, or "[EOG]" when finished.
     */
    std::string completionLoop();

    /**
     * Stop the current completion and free generation resources.
     */
    void stopCompletion();

    /**
     * Get the response generation speed in tokens per second.
     */
    float getResponseGenerationTime() const;

    /**
     * Get the number of tokens in the context window that have been used.
     */
    int getContextSizeUsed() const;

    /**
     * Get the model description string.
     */
    std::string getModelDescription() const;

    /**
     * Unload the model and free all resources.
     */
    void unloadModel();

private:
    // llama.cpp core objects
    llama_model*   _model = nullptr;
    llama_context* _ctx = nullptr;
    llama_sampler* _sampler = nullptr;
    llama_vocab*   _vocab = nullptr;

    // mtmd vision context
    mtmd_context* _mtmdCtx = nullptr;

    // Loaded images
    std::vector<mtmd::bitmap> _bitmaps;

    // Generation state
    llama_token _currToken = 0;
    llama_pos   _nPast = 0;
    std::string _response;
    std::string _cacheResponseTokens;

    // Metrics
    int64_t _responseGenerationTime = 0;
    long    _responseNumTokens = 0;
    int     _nCtxUsed = 0;

    // Parameters
    int _nThreads = 4;

    bool _isValidUtf8(const char* response);
    void resetGenerationState();
};
