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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.shubham0204.smollmandroid.MainActivity
import io.shubham0204.smollmandroid.R
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.android.ext.android.inject
import java.io.File

private const val LOGTAG = "[HttpService-Kt]"
private const val SERVICE_NOTIFICATION_ID = 8765
private const val NOTIFICATION_CHANNEL_ID = "amm_http_service"
private const val HTTP_PORT = 8765

/**
 * Foreground service that hosts an embedded HTTP server (NanoHTTPD) on 127.0.0.1:8765.
 *
 * Endpoints (bp-app contract):
 *   GET  /v1/status       → {"version":"1.1.0","ready":true,"capabilities":["vision"],
 *                            "models":{"vision":"qwen2.5-vl-3b"},"queue_depth":0,
 *                            "inference_mode":"local"}
 *   POST /v1/vision/completions → multipart with `image` (file) and `prompt` (text)
 *   GET  /health          → {"status":"ok"} (legacy)
 *   GET  /status          → {"vision_model_loaded":true/false, "model_name":"..."} (legacy)
 *   POST /vision          → same as /v1/vision/completions (legacy)
 *
 * The service must remain in the foreground while models are loaded to prevent
 * Android from killing the process under memory pressure.
 */
class HttpService : Service() {

    private val visionLMManager: VisionLMManager by inject()
    private var server: VisionHttpServer? = null
    private val json = Json { prettyPrint = false }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_NOTIFICATION_ID, buildNotification())

        if (server == null) {
            server = VisionHttpServer(HTTP_PORT, visionLMManager, this::modelNameProvider)
            try {
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.i(LOGTAG, "HTTP server started on 127.0.0.1:$HTTP_PORT")
            } catch (e: Exception) {
                Log.e(LOGTAG, "Failed to start HTTP server: ${e.message}")
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        server?.stop()
        server = null
        Log.i(LOGTAG, "HTTP server stopped")
        super.onDestroy()
    }

    private fun modelNameProvider(): String {
        return visionLMManager.loadedModelName
            ?: if (visionLMManager.isModelLoaded) "unknown" else "none"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AMM HTTP Service",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the on-device AI model loaded for local app access"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AMM AI Hub")
            .setContentText("Local vision model ready on 127.0.0.1:$HTTP_PORT")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ------------------------------------------------------------------------
    // Inner HTTP Server
    // ------------------------------------------------------------------------

    private inner class VisionHttpServer(
        port: Int,
        private val visionLMManager: VisionLMManager,
        private val modelNameProvider: () -> String,
    ) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            // Handle CORS preflight
            if (session.method == Method.OPTIONS) {
                val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
                response.addHeader("Access-Control-Allow-Origin", "*")
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                response.addHeader("Access-Control-Allow-Headers", "Content-Type")
                response.addHeader("Access-Control-Allow-Private-Network", "true")
                return response
            }

            val uri = session.uri
            val method = session.method

            return when {
                uri == "/v1/status" && method == Method.GET -> {
                    jsonResponse(Response.Status.OK, buildJsonObject {
                        put("version", "1.1.0")
                        put("ready", visionLMManager.isModelLoaded)
                        put("capabilities", buildJsonArray {
                            add(JsonPrimitive("vision"))
                        })
                        put("models", buildJsonObject {
                            put("vision", modelNameProvider())
                        })
                        put("queue_depth", 0)
                        put("inference_mode", "local")
                    })
                }
                (uri == "/v1/vision/completions" || uri == "/vision") && method == Method.POST -> {
                    handleVisionRequest(session)
                }
                uri == "/health" && method == Method.GET -> {
                    jsonResponse(Response.Status.OK, buildJsonObject { put("status", "ok") })
                }
                uri == "/status" && method == Method.GET -> {
                    jsonResponse(Response.Status.OK, buildJsonObject {
                        put("vision_model_loaded", visionLMManager.isModelLoaded)
                        put("model_name", modelNameProvider())
                    })
                }
                else -> {
                    jsonResponse(Response.Status.NOT_FOUND, buildJsonObject {
                        put("error", "Not found")
                        put("available_endpoints", buildJsonArray {
                            add(JsonPrimitive("/v1/status"))
                            add(JsonPrimitive("/v1/vision/completions"))
                            add(JsonPrimitive("/health"))
                            add(JsonPrimitive("/status"))
                            add(JsonPrimitive("/vision"))
                        })
                    })
                }
            }
        }

        private fun handleVisionRequest(session: IHTTPSession): Response {
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val prompt = session.parameters["prompt"]?.firstOrNull()
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, buildJsonObject {
                        put("success", false)
                        put("error", "Missing 'prompt' parameter")
                    })

                val imageFilePath = files["image"]
                    ?: return jsonResponse(Response.Status.BAD_REQUEST, buildJsonObject {
                        put("success", false)
                        put("error", "Missing 'image' file")
                    })

                val imageBytes = File(imageFilePath).readBytes()

                // Run inference (blocking is OK here; NanoHTTPD serves per-thread)
                val result = runBlocking(Dispatchers.IO) {
                    visionLMManager.infer(imageBytes, prompt)
                }

                if (result.success) {
                    jsonResponse(Response.Status.OK, buildJsonObject {
                        put("success", true)
                        put("response", result.response)
                        put("tokens_per_sec", result.generationSpeed)
                        put("context_used", result.contextLengthUsed)
                    })
                } else {
                    jsonResponse(Response.Status.INTERNAL_ERROR, buildJsonObject {
                        put("success", false)
                        put("error", result.error ?: "Inference failed")
                    })
                }
            } catch (e: Exception) {
                Log.e(LOGTAG, "Error handling /vision request: ${e.message}")
                jsonResponse(Response.Status.INTERNAL_ERROR, buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                })
            }
        }

        private fun jsonResponse(status: Response.Status, jsonElement: kotlinx.serialization.json.JsonElement): Response {
            val text = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
            val response = newFixedLengthResponse(status, "application/json", text)
            // CORS headers required for bp-app PWA to reach localhost
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type")
            response.addHeader("Access-Control-Allow-Private-Network", "true")
            return response
        }

    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, HttpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HttpService::class.java))
        }
    }
}
