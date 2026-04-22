# AMM Browser Engine Features Documentation

**Date:** 2026-04-22  
**Current Engine:** Android WebView (Chromium-based)  
**Target Engine:** GeckoView (Firefox-based)  
**Files Affected:** `BrowserActivity.kt`, `BpAppWebViewActivity.kt`, `app/build.gradle.kts`

---

## 1. Current Browser Architecture

AMM contains two browser activities, both based on Android's system `WebView`:

| Activity | Purpose | Usage |
|----------|---------|-------|
| `BrowserActivity` | Full embedded browser with URL bar, nav, bookmarks, PWA support | Launched from Chat drawer for BP Log and general browsing |
| `BpAppWebViewActivity` | Dedicated BP Log wrapper (simpler chrome) | **Dead code** — never launched from anywhere in the app |

### 1.1 WebView Configuration (Common to Both)

```kotlin
settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    databaseEnabled = true            // deprecated but still required for IndexedDB
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
```

| Setting | Purpose | GeckoView Equivalent |
|---------|---------|---------------------|
| `javaScriptEnabled = true` | bp-app is a JS app; bridge requires JS | `GeckoRuntimeSettings.Builder().javaScriptEnabled(true)` (default) |
| `domStorageEnabled = true` | PWA localStorage | Default in GeckoView |
| `databaseEnabled = true` | IndexedDB for bp-app entries | Default in GeckoView |
| `mixedContentMode = MIXED_CONTENT_ALWAYS_ALLOW` | Allows HTTPS PWA → HTTP localhost AMM | `GeckoSessionSettings.USER_AGENT_MODE_MOBILE` + security settings |
| `allowFileAccess = true` | File inputs | Default in GeckoView |
| `allowContentAccess = true` | Content provider access | Default in GeckoView |
| `mediaPlaybackRequiresUserGesture = false` | Auto-play media | `GeckoSessionSettings.ALLOW_MEDIA` |
| `builtInZoomControls = true` | Pinch-zoom | Default in GeckoView |
| `loadWithOverviewMode = true` | Fit page to screen | Viewport settings |
| `useWideViewPort = true` | Desktop-like viewport | Viewport settings |

---

## 2. Feature Inventory: BrowserActivity.kt

### 2.1 Navigation & Chrome

| Feature | WebView API | Status | Notes |
|---------|-------------|--------|-------|
| URL loading | `webView.loadUrl()` | ✅ | |
| Back/forward | `webView.goBack()` / `goForward()` | ✅ | |
| Reload | `webView.reload()` | ✅ | |
| Progress tracking | `WebChromeClient.onProgressChanged()` | ✅ | Shown in LinearProgressIndicator |
| Page title | `WebChromeClient` + `webView.title` | ✅ | |
| URL override (keep in-app) | `WebViewClient.shouldOverrideUrlLoading()` | ✅ | Delegates `mailto:`, `tel:`, `intent:` only |
| Page start/finish callbacks | `WebViewClient.onPageStarted/Finished()` | ✅ | History update, manifest detection |
| Cookie management | `CookieManager` | ✅ | Cleared on init |
| Cache clear | `webView.clearCache(true)` | ✅ | |
| History clear | `webView.clearHistory()` | ✅ | |

### 2.2 JavaScript Bridge (AMM ↔ bp-app)

**Interface name:** `window.AMMBridge`  
**Registration:** `webView.addJavascriptInterface(AmmBridge(), "AMMBridge")`

| Method | Return Type | Purpose |
|--------|-------------|---------|
| `isEmbedded()` | `Boolean` | Always `true` — lets bp-app know it's inside AMM |
| `getAmmVersion()` | `String` | Returns `"1.1.4"` |
| `isHttpServiceRunning()` | `Boolean` | Reads `HttpService.isRunning` |
| `isVisionModelLoaded()` | `Boolean` | Reads `VisionLMManager.isModelLoaded` |
| `getLoadedModelName()` | `String` | Reads `VisionLMManager.loadedModelName` |
| `ammVisionInfer(base64Image, prompt)` | `String` (JSON) | Runs vision inference directly via `VisionLMManager.infer()` — **bypasses HTTP entirely** |

**Critical:** The bridge runs inference on the native thread via `runBlocking(Dispatchers.IO)`. This avoids all CORS/PNA issues because no HTTP request crosses the HTTPS→loopback boundary.

### 2.3 User Input / File Handling

| Feature | WebView API | Status |
|---------|-------------|--------|
| File chooser (photo upload) | `WebChromeClient.onShowFileChooser()` | ✅ Required for bp-app camera/gallery |
| `ActivityResultContracts.StartActivityForResult()` | Launcher pattern | ✅ Returns single or multiple URIs |

### 2.4 Media & Fullscreen

| Feature | WebView API | Status |
|---------|-------------|--------|
| Fullscreen video | `WebChromeClient.onShowCustomView()` / `onHideCustomView()` | ✅ |
| Custom view callback | `WebChromeClient.CustomViewCallback` | ✅ |

### 2.5 Downloads

| Feature | WebView API | Status |
|---------|-------------|--------|
| File downloads | `WebView.setDownloadListener()` | ✅ Uses `DownloadManager` |
| Download notification | `VISIBILITY_VISIBLE_NOTIFY_COMPLETED` | ✅ |
| External public dir | `Environment.DIRECTORY_DOWNLOADS` | ✅ |

### 2.6 Find-in-Page

| Feature | WebView API | Status |
|---------|-------------|--------|
| Async find | `webView.findAllAsync(query)` | ✅ |
| Find next/previous | `webView.findNext(boolean)` | ✅ |
| Clear matches | `webView.clearMatches()` | ✅ |

### 2.7 PWA / Shortcut Support

| Feature | Implementation | Status |
|---------|----------------|--------|
| Manifest detection | Injected JS: `document.querySelector('link[rel="manifest"]')` | ✅ |
| Manifest fetch | OkHttp `Request.Builder().url(manifestUrl)` | ✅ |
| Icon download | OkHttp + `BitmapFactory.decodeByteArray()` | ✅ |
| Home screen shortcut | `ShortcutManagerCompat.requestPinShortcut()` | ✅ |

### 2.8 Data Layer Integration

| Feature | API | Status |
|---------|-----|--------|
| Bookmark CRUD | `AppDB` (Room) | ✅ |
| History CRUD | `AppDB.addOrUpdateHistory()` | ✅ |
| HTTP service toggle | `HttpService.start()` / `stop()` | ✅ |

---

## 3. Feature Inventory: BpAppWebViewActivity.kt

| Feature | Status | Notes |
|---------|--------|-------|
| WebView with same config | ✅ | Same settings as BrowserActivity |
| JS bridge (`ammAndroid`) | ⚠️ **OUTDATED** | Uses old name `ammAndroid`; only has `isEmbedded()` and `getAmmVersion()` |
| File chooser | ✅ | Same pattern as BrowserActivity |
| Console logging | ✅ | `WebChromeClient.onConsoleMessage()` |
| External link delegation | ✅ | Opens non-bp-app URLs in system browser |

**CRITICAL BUG:** `BpAppWebViewActivity` exposes `window.ammAndroid` instead of `window.AMMBridge`, and lacks vision inference methods. However, this activity is **never launched** from anywhere in the app (verified by grep). The Chat drawer launches `BrowserActivity` for BP Log. Therefore this file is dead code.

---

## 4. Root Causes of Debug Log Errors

The debug log provided by the user shows the following errors when bp-app attempts to detect AMM:

```
❌ Page is HTTPS: Mixed-content may block HTTP localhost
✅ WebView detected
❌ Health endpoint unreachable: Failed to fetch
❌ Status endpoint unreachable: Failed to fetch
❌ CORS preflight failed: Failed to fetch
❌ probeAMM() state: AMM not detected
```

### RCA Summary Table

| Symptom | Root Cause | Fix Status |
|---------|-----------|------------|
| Page is HTTPS | bp-app is served from `https://comfac-global-group.github.io/bp-app/`; Chrome PNA blocks HTTPS→HTTP loopback | **Mitigated** by JS bridge (`AMMBridge`) — no HTTP needed |
| Health endpoint unreachable | HTTPS page cannot `fetch()` HTTP `127.0.0.1:8765/health` due to Chrome Private Network Access (PNA) rules | **Mitigated** by JS bridge |
| Status endpoint unreachable | Same PNA block on `/v1/status` | **Mitigated** by JS bridge |
| CORS preflight failed | Request never reaches AMM; blocked at browser level before CORS headers matter | **Mitigated** by JS bridge |
| probeAMM() state: AMM not detected | Fallback HTTP probe fails; `window.AMMBridge` may be missing if running outside AMM | **Fixed in AMM v1.1.4** — `BrowserActivity` now exposes full `AMMBridge` |

### Why the Errors Still Appear

These errors appear when bp-app is opened in **an external browser** (Chrome, Firefox, Safari) rather than inside AMM's embedded browser. In that scenario:

1. `window.AMMBridge` does not exist → bp-app falls back to HTTP probe
2. HTTPS origin → Chrome enforces PNA preflight for loopback
3. Even though AMM `HttpService` now returns `Access-Control-Allow-Private-Network: true`, Chrome may still silently drop the request on mobile if the user has not interacted with the page recently
4. Result: all probes fail, AMM appears "not detected"

**User guidance:** BPLog must be opened from inside AMM (Chat drawer → 🩺 BP Log) for the JS bridge to be available.

---

## 5. GeckoView Migration Plan

### 5.1 Dependency Changes

```kotlin
// settings.gradle.kts — add Mozilla Maven repository
maven { url = URI("https://maven.mozilla.org/maven2") }

// app/build.gradle.kts — add GeckoView (all architectures)
val geckoviewVersion = "132.0.20241110192737"
implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoviewVersion")
implementation("org.mozilla.geckoview:geckoview-armeabi-v7a:$geckoviewVersion")
implementation("org.mozilla.geckoview:geckoview-x86:$geckoviewVersion")
implementation("org.mozilla.geckoview:geckoview-x86_64:$geckoviewVersion")
```

### 5.2 API Mapping: WebView → GeckoView

| WebView Component | GeckoView Equivalent |
|-------------------|---------------------|
| `WebView` | `GeckoView` (custom view) |
| `WebViewClient` | `GeckoSession.NavigationDelegate` |
| `WebChromeClient.onProgressChanged()` | `GeckoSession.ProgressDelegate` |
| `WebChromeClient.onConsoleMessage()` | `GeckoSession.ContentDelegate.onCrash()` + `GeckoRuntimeSettings.consoleOutput(true)` |
| `WebChromeClient.onShowFileChooser()` | `GeckoSession.PromptDelegate.onFilePrompt()` |
| `WebChromeClient.onShowCustomView()` | `GeckoSession.MediaDelegate` |
| `WebView.setDownloadListener()` | `GeckoSession.DownloadDelegate` |
| `WebView.findAllAsync()` | `SessionFinder.find()` |
| `addJavascriptInterface()` | `window.prompt()` interception via `PromptDelegate` (see §5.3) |
| `CookieManager` | `GeckoRuntime.getCookieManager()` |
| `webView.clearCache()` | `GeckoRuntime.storageController.clearData()` |

### 5.3 JS Bridge Strategy for GeckoView

GeckoView does **not** have `addJavascriptInterface`. The standard modern approach is WebExtensions, but that is heavy for a simple bridge. The pragmatic approach is **`window.prompt()` interception**:

**Native side (`PromptDelegate`):**
```kotlin
override fun onPromptPrompt(session, title, msg, defaultValue, callback) {
    if (title == "amm-bridge") {
        val request = JSONObject(msg ?: "{}")
        val response = handleBridgeRequest(request)
        callback.confirm(response.toString())
    }
}
```

**Injected JS:**
```javascript
window.AMMBridge = {
    isEmbedded: () => true,
    getAmmVersion: () => '1.1.4',
    isHttpServiceRunning: () => JSON.parse(window.prompt('amm-bridge', '{"method":"isHttpServiceRunning"}')),
    // ... etc
};
```

This preserves the **exact same API** that bp-app already uses, with zero changes to bp-app.

### 5.4 Known GeckoView Trade-offs

| Aspect | WebView (Chromium) | GeckoView (Firefox) |
|--------|-------------------|---------------------|
| APK size increase | ~0 (system component) | ~40–60 MB per architecture |
| Memory footprint | Shared with Chrome | Independent process |
| PNA / mixed-content | Chrome PNA rules apply | Gecko has different (often looser) localhost policies |
| JS bridge | `addJavascriptInterface` (simple) | `prompt()` hack or WebExtension (more code) |
| Auto-update | Updates with Chrome/WebView system updates | Bundled with app; updates with app releases |
| Privacy | Shares state with Chrome | Fully isolated from system browser |
| Consistency | Varies by Android version / Chrome version | Identical across all Android versions |

---

## 6. Decision Log

| Decision | Rationale |
|----------|-----------|
| Remove `BpAppWebViewActivity.kt` | Dead code; never launched from any UI path. `BrowserActivity` handles BP Log via `DEFAULT_URL`. Removing eliminates maintenance burden and confusion. |
| Use `window.prompt()` bridge | Only viable synchronous bridge in GeckoView without WebExtensions. Preserves bp-app API contract. |
| Include all 4 GeckoView architectures | Existing app builds for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` via llama.cpp. Parity maintained. APK size grows but App Bundle / APK splits keep per-device download reasonable. |
| Migrate only `BrowserActivity` | Single browser entry point simplifies codebase. BP Log opens in full browser with URL bar hidden or collapsed if desired later. |
