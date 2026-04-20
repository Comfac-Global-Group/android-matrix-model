#include "LLMInference.h"
#include "VisionInference.h"
#include <jni.h>
#include <android/bitmap.h>

// ============================================================================
// Text Model JNI Methods (existing)
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_SmolLM_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                            jfloat temperature, jboolean storeChats, jlong contextSize,
                                            jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, storeChats, contextSize, chatTemplateCstr, nThreads,
                                useMmap, useMlock);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr, jstring message,
                                                 jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_shubham0204_smollm_SmolLM_getResponseGenerationSpeed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_SmolLM_getContextSizeUsed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr, jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_SmolLM_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_SmolLM_benchModel(JNIEnv* env, jobject /*unused*/, jlong modelPtr, jint pp, jint tg, jint pl,
                                             jint nr) {
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    std::string result       = llmInference->benchModel(pp, tg, pl, nr);
    return env->NewStringUTF(result.c_str());
}

// ============================================================================
// Vision Model JNI Methods (new)
// ============================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_io_shubham0204_smollm_SmolLM_loadVisionModelNative(JNIEnv* env, jobject /*thiz*/, jstring modelPath, jstring mmprojPath,
                                                  jint nThreads) {
    jboolean    isCopy        = true;
    const char* modelPathCstr = env->GetStringUTFChars(modelPath, &isCopy);
    const char* mmprojPathCstr = env->GetStringUTFChars(mmprojPath, &isCopy);

    auto* visionInference = new VisionInference();
    bool  success         = visionInference->loadModel(modelPathCstr, mmprojPathCstr, nThreads);

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(mmprojPath, mmprojPathCstr);

    if (!success) {
        delete visionInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to load vision model");
        return 0;
    }

    return reinterpret_cast<jlong>(visionInference);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_shubham0204_smollm_SmolLM_loadVisionImage(JNIEnv* env, jobject /*thiz*/, jlong modelPtr, jbyteArray imageBuffer) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);

    jsize   len     = env->GetArrayLength(imageBuffer);
    jbyte*  buf     = env->GetByteArrayElements(imageBuffer, nullptr);
    bool    success = visionInference->loadImageFromBuffer(reinterpret_cast<const unsigned char*>(buf), len);

    env->ReleaseByteArrayElements(imageBuffer, buf, JNI_ABORT);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_clearVisionImages(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    visionInference->clearImages();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_shubham0204_smollm_SmolLM_startVisionCompletion(JNIEnv* env, jobject /*thiz*/, jlong modelPtr, jstring prompt) {
    jboolean    isCopy      = true;
    const char* promptCstr  = env->GetStringUTFChars(prompt, &isCopy);
    auto*       visionInference = reinterpret_cast<VisionInference*>(modelPtr);

    bool success = visionInference->startCompletion(promptCstr);

    env->ReleaseStringUTFChars(prompt, promptCstr);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_shubham0204_smollm_SmolLM_visionCompletionLoop(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    std::string response = visionInference->completionLoop();
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_stopVisionCompletion(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    visionInference->stopCompletion();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_io_shubham0204_smollm_SmolLM_getVisionResponseGenerationSpeed(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    return visionInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_shubham0204_smollm_SmolLM_getVisionContextSizeUsed(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    return visionInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_io_shubham0204_smollm_SmolLM_closeVisionModel(JNIEnv* env, jobject /*thiz*/, jlong modelPtr) {
    auto* visionInference = reinterpret_cast<VisionInference*>(modelPtr);
    delete visionInference;
}
