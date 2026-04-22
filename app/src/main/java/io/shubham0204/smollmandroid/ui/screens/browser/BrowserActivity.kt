/*
 * Copyright (C) 2025 AMM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.shubham0204.smollmandroid.ui.screens.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import io.shubham0204.smollmandroid.data.AppDB
import io.shubham0204.smollmandroid.llm.HttpService
import io.shubham0204.smollmandroid.llm.VisionLMManager
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koin.android.ext.android.inject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import java.net.URL

/**
 * Full browser activity for AMM using GeckoView (Firefox engine).
 * Self-contained — never delegates http/https to external browsers.
 * Supports bookmarks, history, downloads, find-in-page, fullscreen video, and PWA "Add to Home Screen".
 *
 * Migration from WebView (Chromium) → GeckoView (Firefox):
 * - WebView replaced by GeckoView + GeckoSession
 * - WebViewClient replaced by NavigationDelegate
 * - WebChromeClient replaced by ProgressDelegate + ContentDelegate + PromptDelegate
 * - addJavascriptInterface replaced by window.prompt() interception (GeckoView has no native JS bridge)
 * - Downloads handled via ContentDelegate.onExternalResponse
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val DEFAULT_URL = "https://comfac-global-group.github.io/bp-app/"
    }

    private val appDB: AppDB by inject()
    private val visionLMManager: VisionLMManager by inject()
    private val okHttpClient = OkHttpClient()

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var geckoRuntime: GeckoRuntime
    private var pendingFilePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private var customView: View? = null
    private var sessionCurrentUrl: String = ""
    private var sessionTitle: String = ""

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prompt = pendingFilePrompt
            val gr = pendingFileResult
            if (prompt != null && gr != null) {
                if (result.resultCode == Activity.RESULT_OK) {
                    val clipData = result.data?.clipData
                    val uri = result.data?.data
                    val results: Array<Uri>? = when {
                        clipData != null && clipData.itemCount > 0 -> {
                            Array(clipData.itemCount) { clipData.getItemAt(it).uri }
                        }
                        uri != null -> arrayOf(uri)
                        else -> null
                    }
                    if (results != null) {
                        gr.complete(prompt.confirm(this@BrowserActivity, results))
                    } else {
                        gr.complete(prompt.dismiss())
                    }
                } else {
                    gr.complete(prompt.dismiss())
                }
                pendingFilePrompt = null
                pendingFileResult = null
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
        setContent {
            SmolLMAndroidTheme {
                BrowserScreen(
                    initialUrl = initialUrl,
                    onBack = { finish() }
                )
            }
        }
    }

    private fun getOrCreateRuntime(): GeckoRuntime {
        if (!::geckoRuntime.isInitialized) {
            val settings = GeckoRuntimeSettings.Builder()
                .debugLogging(true)
                .build()
            geckoRuntime = GeckoRuntime.create(this, settings)
        }
        return geckoRuntime
    }

    private fun createConfiguredGeckoView(
        onPageStarted: () -> Unit,
        onPageFinished: (String) -> Unit,
        onProgress: (Int) -> Unit,
        onCanGoBackChanged: (Boolean) -> Unit,
        onCanGoForwardChanged: (Boolean) -> Unit,
        onTitleChanged: (String) -> Unit,
        onUrlChanged: (String) -> Unit,
        onManifestDetected: (String) -> Unit,
        onFullscreen: (Boolean) -> Unit,
    ): GeckoView {
        val runtime = getOrCreateRuntime()

        geckoSession = GeckoSession(GeckoSessionSettings.Builder().build()).apply {
            // Progress tracking
            progressDelegate = object : GeckoSession.ProgressDelegate {
                override fun onPageStart(session: GeckoSession, url: String) {
                    onPageStarted()
                }

                override fun onPageStop(session: GeckoSession, success: Boolean) {
                    onPageFinished(sessionCurrentUrl)
                    onTitleChanged.invoke(sessionTitle)
                    onUrlChanged.invoke(sessionCurrentUrl)

                    // Inject AMM JS bridge after page load
                    injectAmmBridge()

                    // Detect manifest
                    val manifestScript = """
                        (function() {
                            var link = document.querySelector('link[rel="manifest"]');
                            if (link) return link.href;
                            return '';
                        })()
                    """.trimIndent()
                    session.loadUri("javascript:$manifestScript")
                }

                override fun onProgressChange(session: GeckoSession, progress: Int) {
                    onProgress(progress)
                }

                override fun onSecurityChange(
                    session: GeckoSession,
                    securityInfo: GeckoSession.ProgressDelegate.SecurityInformation
                ) {
                    // no-op
                }
            }

            // Navigation handling
            navigationDelegate = object : GeckoSession.NavigationDelegate {
                override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                    onCanGoBackChanged(canGoBack)
                }

                override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                    onCanGoForwardChanged(canGoForward)
                }

                override fun onLoadRequest(
                    session: GeckoSession,
                    request: GeckoSession.NavigationDelegate.LoadRequest
                ): GeckoResult<AllowOrDeny>? {
                    val url = request.uri
                    return when {
                        url.startsWith("mailto:") -> {
                            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                            GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                        url.startsWith("tel:") -> {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                            GeckoResult.fromValue(AllowOrDeny.DENY)
                        }
                        url.startsWith("intent:") -> {
                            try {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                if (intent.resolveActivity(packageManager) != null) {
                                    startActivity(intent)
                                    GeckoResult.fromValue(AllowOrDeny.DENY)
                                } else {
                                    null // ALLOW
                                }
                            } catch (e: Exception) {
                                null // ALLOW
                            }
                        }
                        else -> {
                            // Keep all http/https inside the browser
                            null // ALLOW
                        }
                    }
                }
            }

            // Prompts: file chooser + JS bridge via window.prompt()
            promptDelegate = object : GeckoSession.PromptDelegate {
                override fun onFilePrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.FilePrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    pendingFilePrompt = prompt
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    pendingFileResult = result

                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        val mimeTypes = prompt.mimeTypes
                        if (mimeTypes != null && mimeTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                        }
                        if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    }
                    fileChooserLauncher.launch(intent)
                    return result
                }

                override fun onTextPrompt(
                    session: GeckoSession,
                    prompt: GeckoSession.PromptDelegate.TextPrompt
                ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                    // Intercept bridge prompts (window.prompt uses text prompt)
                    // window.prompt(message, defaultValue) -> prompt.message = message, prompt.defaultValue = defaultValue
                    if (prompt.message == "amm-bridge") {
                        val response = handleBridgeRequest(prompt.defaultValue ?: "{}")
                        return GeckoResult.fromValue(prompt.confirm(response))
                    }
                    return super.onTextPrompt(session, prompt)
                }
            }

            // Content delegate: console messages, fullscreen, downloads
            contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCrash(session: GeckoSession) {
                    android.util.Log.e("BrowserActivity", "GeckoSession crashed")
                }

                override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                    onFullscreen(fullScreen)
                }

                override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                    // Handle downloads
                    val url = response.uri
                    val fileName = response.headers["Content-Disposition"]
                        ?.let { cd ->
                            val match = Regex("filename=\"?([^\";]+)\"?").find(cd)
                            match?.groupValues?.get(1)
                        }
                        ?: url.substringAfterLast('/').substringBefore('?')
                        .ifBlank { "download" }
                    val mimeType = response.headers["Content-Type"] ?: "application/octet-stream"

                    val dmRequest = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        setDescription("Downloading file...")
                        setTitle(fileName)
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(dmRequest)
                }
            }

            open(runtime)
        }

        geckoView = GeckoView(this).apply {
            setSession(geckoSession)
        }

        // Clear cookies and storage for a fresh session
        runtime.storageController.clearData(
            StorageController.ClearFlags.ALL
        )

        return geckoView
    }

    /**
     * Injects window.AMMBridge into the page using a javascript: URL.
     * This preserves the exact same API that bp-app expects.
     */
    private fun injectAmmBridge() {
        val bridgeScript = """
        (function() {
            if (window.AMMBridge) return;
            window.AMMBridge = {
                isEmbedded: function() { return true; },
                getAmmVersion: function() { return '1.1.4'; },
                isHttpServiceRunning: function() {
                    return JSON.parse(window.prompt('amm-bridge', '{"method":"isHttpServiceRunning"}'));
                },
                isVisionModelLoaded: function() {
                    return JSON.parse(window.prompt('amm-bridge', '{"method":"isVisionModelLoaded"}'));
                },
                getLoadedModelName: function() {
                    return window.prompt('amm-bridge', '{"method":"getLoadedModelName"}');
                },
                ammVisionInfer: function(base64Image, prompt) {
                    return window.prompt('amm-bridge', JSON.stringify({
                        method: 'ammVisionInfer',
                        base64Image: base64Image,
                        prompt: prompt
                    }));
                }
            };
        })();
        """.trimIndent().replace("\n", " ")
        geckoSession.loadUri("javascript:$bridgeScript")
    }

    private fun handleBridgeRequest(msg: String): String {
        return try {
            val request = JSONObject(msg)
            when (request.optString("method")) {
                "isHttpServiceRunning" -> HttpService.isRunning.toString()
                "isVisionModelLoaded" -> visionLMManager.isModelLoaded.toString()
                "getLoadedModelName" -> (visionLMManager.loadedModelName ?: "none")
                "ammVisionInfer" -> {
                    val base64Image = request.optString("base64Image", "")
                    val prompt = request.optString("prompt", "")
                    val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                    val result = runBlocking(Dispatchers.IO) {
                        visionLMManager.infer(imageBytes, prompt)
                    }
                    JSONObject().apply {
                        put("success", result.success)
                        put("response", result.response)
                        put("tokens_per_sec", result.generationSpeed)
                        put("context_used", result.contextLengthUsed)
                        if (result.error != null) put("error", result.error)
                    }.toString()
                }
                else -> "{\"error\":\"unknown method\"}"
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("success", false)
                put("error", e.message ?: "Bridge error")
            }.toString()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::geckoSession.isInitialized) {
            // Use navigation delegate state if available, or just try goBack
            geckoSession.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::geckoSession.isInitialized) {
            geckoSession.close()
        }
        super.onDestroy()
    }

    // --- PWA / Shortcut helpers ---

    fun addToHomeScreen(manifestUrl: String, pageUrl: String, pageTitle: String, onResult: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val manifestJson = fetchManifest(manifestUrl)
                val name = manifestJson?.optString("short_name")
                    ?: manifestJson?.optString("name")
                    ?: pageTitle
                val startUrl = manifestJson?.optString("start_url") ?: pageUrl
                val iconUrl = manifestJson?.let { extractBestIconUrl(it, manifestUrl) } ?: ""
                val iconBitmap = if (iconUrl.isNotBlank()) downloadBitmap(iconUrl) else null

                val shortcutIntent = Intent(this@BrowserActivity, BrowserActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(startUrl)
                    putExtra(EXTRA_URL, startUrl)
                }

                val shortcutBuilder = ShortcutInfoCompat.Builder(this@BrowserActivity, "pwa_${System.currentTimeMillis()}")
                    .setShortLabel(name.take(12))
                    .setLongLabel(name)
                    .setIntent(shortcutIntent)

                if (iconBitmap != null) {
                    shortcutBuilder.setIcon(IconCompat.createWithBitmap(iconBitmap))
                } else {
                    shortcutBuilder.setIcon(IconCompat.createWithResource(this@BrowserActivity, android.R.drawable.ic_menu_gallery))
                }

                val shortcut = shortcutBuilder.build()
                val success = ShortcutManagerCompat.requestPinShortcut(this@BrowserActivity, shortcut, null)

                withContext(Dispatchers.Main) {
                    onResult(if (success) "Added '$name' to home screen" else "Home screen shortcut not supported")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult("Error: ${e.message}")
                }
            }
        }
    }

    private fun fetchManifest(manifestUrl: String): JSONObject? {
        return try {
            val request = Request.Builder().url(manifestUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                JSONObject(response.body?.string() ?: "")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractBestIconUrl(manifest: JSONObject, manifestUrl: String): String {
        val icons = manifest.optJSONArray("icons") ?: return ""
        var bestUrl = ""
        var bestSize = 0
        for (i in 0 until icons.length()) {
            val icon = icons.getJSONObject(i)
            val src = icon.optString("src", "")
            val sizes = icon.optString("sizes", "")
            val size = sizes.split("x").firstOrNull()?.toIntOrNull() ?: 0
            if (size in (bestSize + 1)..192) {
                bestSize = size
                bestUrl = src
            }
        }
        if (bestUrl.isBlank() && icons.length() > 0) {
            bestUrl = icons.getJSONObject(0).optString("src", "")
        }
        return if (bestUrl.startsWith("http")) bestUrl else {
            val base = URL(manifestUrl)
            URL(base, bestUrl).toString()
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun BrowserScreen(initialUrl: String, onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        val snackbarHostState = remember { SnackbarHostState() }
        val focusManager = LocalFocusManager.current

        var currentUrl by remember { mutableStateOf(initialUrl) }
        var urlInput by remember { mutableStateOf(initialUrl) }
        var isLoading by remember { mutableStateOf(true) }
        var progress by remember { mutableStateOf(0) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        var pageTitle by remember { mutableStateOf("") }
        var isBookmarked by remember { mutableStateOf(false) }
        var showMenu by remember { mutableStateOf(false) }
        var showFindBar by remember { mutableStateOf(false) }
        var findQuery by remember { mutableStateOf("") }
        var manifestUrl by remember { mutableStateOf("") }
        var showAddToHome by remember { mutableStateOf(false) }
        var isFullscreen by remember { mutableStateOf(false) }
        var httpServiceRunning by remember { mutableStateOf(HttpService.isRunning) }

        LaunchedEffect(currentUrl) {
            isBookmarked = appDB.isBookmarked(currentUrl)
        }

        // Poll HTTP service status periodically
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                httpServiceRunning = HttpService.isRunning
            }
        }

        LaunchedEffect(manifestUrl) {
            showAddToHome = manifestUrl.isNotBlank()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    if (!isFullscreen) {
                        TopAppBar(
                            title = {
                                TextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    placeholder = { Text("Enter URL") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = {
                                        focusManager.clearFocus()
                                        var url = urlInput.trim()
                                        if (url.isBlank()) return@KeyboardActions
                                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                            url = "https://$url"
                                        }
                                        geckoSession.loadUri(url)
                                    })
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                }
                            },
                            actions = {
                                val context = LocalContext.current
                                // HTTP Service status chip
                                androidx.compose.material3.FilterChip(
                                    onClick = {
                                        if (httpServiceRunning) {
                                            HttpService.stop(context)
                                            httpServiceRunning = false
                                            scope.launch { snackbarHostState.showSnackbar("HTTP service stopped") }
                                        } else {
                                            HttpService.start(context)
                                            scope.launch {
                                                kotlinx.coroutines.delay(800)
                                                httpServiceRunning = HttpService.isRunning
                                                snackbarHostState.showSnackbar(
                                                    if (httpServiceRunning) "HTTP service started" else "Failed to start HTTP service"
                                                )
                                            }
                                        }
                                    },
                                    label = {
                                        Text(
                                            if (httpServiceRunning) "AI ON" else "AI OFF",
                                            style = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                                        )
                                    },
                                    selected = httpServiceRunning,
                                    leadingIcon = {
                                        androidx.compose.material3.Icon(
                                            imageVector = if (httpServiceRunning) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                                IconButton(onClick = {
                                    if (isBookmarked) {
                                        val bookmark = appDB.getBookmarkByUrl(currentUrl)
                                        if (bookmark != null) {
                                            appDB.deleteBookmark(bookmark.id)
                                            isBookmarked = false
                                            scope.launch { snackbarHostState.showSnackbar("Bookmark removed") }
                                        }
                                    } else {
                                        appDB.addBookmark(pageTitle.ifBlank { currentUrl }, currentUrl)
                                        isBookmarked = true
                                        scope.launch { snackbarHostState.showSnackbar("Bookmark added") }
                                    }
                                }) {
                                    Icon(
                                        if (isBookmarked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        contentDescription = "Bookmark"
                                    )
                                }
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Find in page") },
                                        onClick = {
                                            showMenu = false
                                            showFindBar = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("History") },
                                        onClick = {
                                            showMenu = false
                                            scope.launch { snackbarHostState.showSnackbar("History: ${appDB.getRecentHistory().size} recent items") }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Clear cache") },
                                        onClick = {
                                            showMenu = false
                                            geckoRuntime.storageController.clearData(
                                                StorageController.ClearFlags.ALL
                                            )
                                            scope.launch { snackbarHostState.showSnackbar("Cache cleared") }
                                        }
                                    )
                                }
                            }
                        )
                        AnimatedVisibility(visible = isLoading && progress < 100) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                floatingActionButton = {
                    if (showAddToHome && !isFullscreen) {
                        FloatingActionButton(
                            onClick = {
                                addToHomeScreen(manifestUrl, currentUrl, pageTitle) { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Add to home screen")
                        }
                    }
                }
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    if (!isFullscreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (canGoBack) geckoSession.goBack() },
                                enabled = canGoBack
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            IconButton(
                                onClick = { if (canGoForward) geckoSession.goForward() },
                                enabled = canGoForward
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                            }
                            IconButton(onClick = { geckoSession.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(onClick = {
                                var url = urlInput.trim()
                                if (url.isBlank()) return@IconButton
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    url = "https://$url"
                                }
                                geckoSession.loadUri(url)
                            }) {
                                Icon(Icons.Default.Search, contentDescription = "Go")
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }

                        AnimatedVisibility(visible = showFindBar) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = findQuery,
                                    onValueChange = {
                                        findQuery = it
                                        if (it.isNotBlank()) {
                                            geckoSession.finder.find(it, GeckoSession.FINDER_FIND_FORWARD)
                                                .accept { result ->
                                                    // result contains match count
                                                }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("Find in page...") }
                                )
                                IconButton(onClick = {
                                    geckoSession.finder.find(findQuery, GeckoSession.FINDER_FIND_FORWARD)
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                                }
                                IconButton(onClick = {
                                    geckoSession.finder.find(findQuery, GeckoSession.FINDER_FIND_BACKWARDS)
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
                                }
                                IconButton(onClick = {
                                    geckoSession.finder.clear()
                                    showFindBar = false
                                    findQuery = ""
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = {
                                createConfiguredGeckoView(
                                    onPageStarted = { isLoading = true; progress = 0 },
                                    onPageFinished = { url ->
                                        isLoading = false
                                        progress = 100
                                        currentUrl = url
                                        urlInput = url
                                        appDB.addOrUpdateHistory(pageTitle.ifBlank { url }, url)
                                    },
                                    onProgress = { progress = it },
                                    onCanGoBackChanged = { canGoBack = it },
                                    onCanGoForwardChanged = { canGoForward = it },
                                    onTitleChanged = { pageTitle = it },
                                    onUrlChanged = { url -> currentUrl = url; urlInput = url },
                                    onManifestDetected = { manifestUrl = it },
                                    onFullscreen = { isFullscreen = it }
                                ).also { gv ->
                                    gv.setSession(geckoSession)
                                    geckoSession.loadUri(initialUrl)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isFullscreen && customView != null) {
                            AndroidView(
                                factory = { customView!! },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
