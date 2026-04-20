/*
 * Copyright (C) 2025 AMM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.shubham0204.smollmandroid.llm

import android.util.Log
import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import org.koin.core.annotation.Single

private const val LOGTAG = "[VisionLMManager-Kt]"

/**
 * Manages the lifecycle of a vision-capable LLM (VLM) for HTTP service inference.
 * Unlike SmolLMManager which is chat-oriented, this is stateless per-request.
 */
@Single
class VisionLMManager {
    private val instance = SmolLM()
    private val mutex = Mutex()

    @Volatile
    private var modelInitJob: Job? = null

    @Volatile
    private var inferenceJob: Job? = null

    @Volatile
    var isModelLoaded = false
        private set

    data class VisionResponse(
        val response: String,
        val generationSpeed: Float,
        val contextLengthUsed: Int,
        val success: Boolean,
        val error: String? = null,
    )

    /**
     * Load a vision model (text GGUF + mmproj projector).
     */
    suspend fun loadModel(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int = 4,
    ): Result<Unit> = mutex.withLock {
        modelInitJob?.cancel()

        return try {
            modelInitJob = CoroutineScope(Dispatchers.IO).launch {
                instance.loadVisionModel(modelPath, mmprojPath, nThreads)
            }
            modelInitJob?.join()
            isModelLoaded = true
            Log.i(LOGTAG, "Vision model loaded: $modelPath + $mmprojPath")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isModelLoaded = false
            Log.e(LOGTAG, "Failed to load vision model: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Run vision inference on the given image bytes with the given prompt.
     * This is a blocking suspend function that returns the complete response.
     */
    suspend fun infer(
        imageBytes: ByteArray,
        prompt: String,
    ): VisionResponse = mutex.withLock {
        if (!isModelLoaded) {
            return VisionResponse(
                response = "",
                generationSpeed = 0f,
                contextLengthUsed = 0,
                success = false,
                error = "Vision model not loaded",
            )
        }

        return try {
            instance.clearVisionImages()
            val imageLoaded = instance.loadVisionImage(imageBytes)
            if (!imageLoaded) {
                return VisionResponse(
                    response = "",
                    generationSpeed = 0f,
                    contextLengthUsed = 0,
                    success = false,
                    error = "Failed to load image",
                )
            }

            var response = ""
            instance.getVisionResponseAsFlow(prompt).collect { piece ->
                response += piece
            }

            VisionResponse(
                response = response.trim(),
                generationSpeed = instance.getVisionResponseGenerationSpeed(),
                contextLengthUsed = instance.getVisionContextLengthUsed(),
                success = true,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOGTAG, "Vision inference error: ${e.message}")
            VisionResponse(
                response = "",
                generationSpeed = 0f,
                contextLengthUsed = 0,
                success = false,
                error = e.message,
            )
        }
    }

    fun unload() {
        modelInitJob?.cancel()
        inferenceJob?.cancel()
        instance.close()
        isModelLoaded = false
    }
}
