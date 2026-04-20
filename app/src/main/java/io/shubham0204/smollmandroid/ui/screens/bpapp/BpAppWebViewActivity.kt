/*
 * Copyright (C) 2025 AMM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package io.shubham0204.smollmandroid.ui.screens.bpapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme

/**
 * Activity that loads bp-app (https://bp.comfac-it.com) in a WebView.
 *
 * Critical configuration:
 * - mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW
 *   This lets the HTTPS PWA reach the HTTP localhost AMM service (127.0.0.1:8765)
 *   without triggering Chrome's Private Network Access (PNA) block.
 * - domStorageEnabled = true — required for PWA localStorage
 * - databaseEnabled = true — required for IndexedDB
 * - javascriptEnabled = true — bp-app is a JS app
 * - WebChromeClient with file chooser — required for photo upload
 */
class BpAppWebViewActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
        setContent {
            SmolLMAndroidTheme {
                BpAppWebViewScreen(
                    onBack = { finish() },
                    onCreateWebView = { createConfiguredWebView() }
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(): WebView {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Open external links in the system browser
                    if (!url.startsWith("https://bp.comfac-it.com")) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    // Log JS console messages for debugging
                    android.util.Log.d(
                        "BpAppWebView",
                        "[${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}] ${consoleMessage?.message()}"
                    )
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    this@BpAppWebViewActivity.filePathCallback = filePathCallback
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                }
            }

            // Inject a JS bridge so bp-app can detect it's running inside AMM
            addJavascriptInterface(AmmBridge(), "ammAndroid")

            loadUrl("https://bp.comfac-it.com")
        }
        return webView
    }

    /**
     * JS bridge exposed to the web page as `window.ammAndroid`.
     * bp-app can call `window.ammAndroid.isEmbedded()` to detect
     * that it's running inside AMM and adjust its UI accordingly.
     */
    inner class AmmBridge {
        @JavascriptInterface
        fun isEmbedded(): Boolean = true

        @JavascriptInterface
        fun getAmmVersion(): String = "1.0.0"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // If WebView can go back, go back in history; otherwise exit activity
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BpAppWebViewScreen(
    onBack: () -> Unit,
    onCreateWebView: () -> WebView,
) {
    var isLoading by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BP Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { context ->
                    onCreateWebView().apply {
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}
