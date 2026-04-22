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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import androidx.lifecycle.lifecycleScope
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.net.URL

/**
 * Full browser activity for AMM. Self-contained — never delegates http/https to external browsers.
 * Supports bookmarks, history, downloads, find-in-page, fullscreen video, and PWA "Add to Home Screen".
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        const val DEFAULT_URL = "https://comfac-global-group.github.io/bp-app/"
    }

    private val appDB: AppDB by inject()
    private val visionLMManager: VisionLMManager by inject()
    private val okHttpClient = OkHttpClient()

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
                filePathCallback?.onReceiveValue(results)
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(
        onPageStarted: () -> Unit,
        onPageFinished: (String) -> Unit,
        onProgress: (Int) -> Unit,
        onCanGoBackChanged: (Boolean) -> Unit,
        onCanGoForwardChanged: (Boolean) -> Unit,
        onTitleChanged: (String) -> Unit,
        onUrlChanged: (String) -> Unit,
        onManifestDetected: (String) -> Unit,
        onFullscreen: (Boolean) -> Unit,
    ): WebView {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return when {
                        url.startsWith("mailto:") -> {
                            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url)))
                            true
                        }
                        url.startsWith("tel:") -> {
                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                            true
                        }
                        url.startsWith("intent:") -> {
                            try {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                if (intent.resolveActivity(packageManager) != null) {
                                    startActivity(intent)
                                    true
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                false
                            }
                        }
                        else -> {
                            // Keep all http/https and other URLs inside the WebView
                            false
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    onPageStarted()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { onPageFinished(it) }
                    onCanGoBackChanged.invoke(canGoBack())
                    onCanGoForwardChanged.invoke(canGoForward())
                    onTitleChanged.invoke(title ?: "")
                    onUrlChanged.invoke(url ?: "")

                    // Inject JS to detect manifest
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var link = document.querySelector('link[rel="manifest"]');
                            if (link) return link.href;
                            return '';
                        })()
                        """.trimIndent()
                    ) { result ->
                        val manifestUrl = result?.trim('"') ?: ""
                        if (manifestUrl.isNotBlank()) {
                            onManifestDetected(manifestUrl)
                        }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    onProgress(newProgress)
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    android.util.Log.d(
                        "BrowserActivity",
                        "[${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}] ${consoleMessage?.message()}"
                    )
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    this@BrowserActivity.filePathCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    customView = view
                    customViewCallback = callback
                    onFullscreen(true)
                }

                override fun onHideCustomView() {
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                    onFullscreen(false)
                }
            }

            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Downloading file...")
                    setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype))
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
            }

            clearCache(true)
            clearHistory()
            CookieManager.getInstance().removeAllCookies(null)

            addJavascriptInterface(AmmBridge(), "AMMBridge")
        }
        return webView
    }

    inner class AmmBridge {
        @JavascriptInterface
        fun isEmbedded(): Boolean = true

        @JavascriptInterface
        fun getAmmVersion(): String = "1.1.4"

        @JavascriptInterface
        fun isHttpServiceRunning(): Boolean = HttpService.isRunning

        @JavascriptInterface
        fun isVisionModelLoaded(): Boolean = visionLMManager.isModelLoaded

        @JavascriptInterface
        fun getLoadedModelName(): String = visionLMManager.loadedModelName ?: "none"

        @JavascriptInterface
        fun ammVisionInfer(base64Image: String, prompt: String): String {
            return try {
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
            } catch (e: Exception) {
                JSONObject().apply {
                    put("success", false)
                    put("error", e.message ?: "Bridge inference failed")
                }.toString()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
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
                                        webView.loadUrl(url)
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
                                            webView.clearCache(true)
                                            CookieManager.getInstance().removeAllCookies(null)
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
                                onClick = { if (canGoBack) webView.goBack() },
                                enabled = canGoBack
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                            IconButton(
                                onClick = { if (canGoForward) webView.goForward() },
                                enabled = canGoForward
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                            }
                            IconButton(onClick = { webView.reload() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                            IconButton(onClick = {
                                var url = urlInput.trim()
                                if (url.isBlank()) return@IconButton
                                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                                    url = "https://$url"
                                }
                                webView.loadUrl(url)
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
                                        webView.findAllAsync(it)
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    placeholder = { Text("Find in page...") }
                                )
                                IconButton(onClick = { webView.findNext(true) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
                                }
                                IconButton(onClick = { webView.findNext(false) }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous")
                                }
                                IconButton(onClick = {
                                    webView.clearMatches()
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
                                createConfiguredWebView(
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
                                ).also { wv ->
                                    wv.loadUrl(initialUrl)
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
