# RCA: bp-app Does Not Detect AMM Vision Model

**Date:** 2026-04-22  
**Status:** Open — specification/implementation gap identified  
**Severity:** High (blocks bp-app ↔ AMM integration)  

---

## Symptom

- AMM Vision Hub shows: **"Model: Loaded"** and **"HTTP: Running on 127.0.0.1:8765"**
- bp-app (browser PWA at `https://comfac-global-group.github.io/bp-app/`) does **not** show AMM as available
- No "AMM detected" pill in bp-app Settings
- bp-app falls back to browser-based 7-segment template matcher or manual entry

---

## Root Cause Analysis

### Finding 1: AMM's HTTP Service Does Not Implement the bp-app Contract

The **vision-models-fdr.md §10** defined a wire-level contract between bp-app and AMM:

```http
GET http://127.0.0.1:8765/v1/status
→ 200 OK
  {
    "version": "1.0.0",
    "ready": true,
    "capabilities": ["vision"],
    "models": { "vision": "qwen2.5-vl-3b" },
    "queue_depth": 0,
    "inference_mode": "local"
  }
```

But the **actual implementation** in `HttpService.kt` uses completely different paths and response shapes:

```kotlin
// HttpService.kt (actual)
GET /health  → {"status":"ok"}
GET /status  → {"vision_model_loaded": true, "model_name": "loaded"}
POST /vision → multipart handler
```

**Gaps:**

| Contract Spec | Actual Implementation | Mismatch |
|--------------|----------------------|----------|
| `/v1/status` | `/status` (no `/v1/`) | ❌ Path |
| `/v1/vision/completions` | `/vision` (no `/v1/`, no `/completions`) | ❌ Path |
| Response field `ready` | Response field `vision_model_loaded` | ❌ Field name |
| Response field `capabilities` | Not present | ❌ Missing |
| Response field `models` | Response field `model_name` with value `"loaded"` | ❌ Wrong shape |
| Response field `version` | Not present | ❌ Missing |
| Response field `queue_depth` | Not present | ❌ Missing |

**Impact:** Even if bp-app were sending requests to `127.0.0.1:8765`, it would receive `404 Not Found` on `/v1/status` or a JSON object with none of the expected fields.

---

### Finding 2: bp-app Has Not Implemented AMM Detection Code Yet

Searching `bp-app/app.js` for AMM-specific integration:

```bash
grep -n "127.0.0.1\|localhost\|amm\|AMM" bp-app/app.js
# → No matches
```

The bp-app codebase contains:
- ✅ 7-segment template matcher (pure JS fallback)
- ✅ Ollama integration (configurable URL)
- ✅ OpenAI-compatible API integration
- ❌ **No AMM probe logic** — no `fetch('http://127.0.0.1:8765/...')` anywhere

The BP-FRD.md *specifies* that AMM should be probed, but the **implementation in bp-app has not been written yet**.

---

### Finding 3: CORS / PNA Preflight Headers Are Insufficient

Chrome's **Private Network Access (PNA)** rules require specific headers when an HTTPS page calls an HTTP localhost server:

```
Access-Control-Allow-Origin: https://comfac-global-group.github.io/bp-app/
Access-Control-Allow-Methods: GET, POST, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Allow-Private-Network: true   ← MISSING in HttpService.kt
```

AMM's current `OPTIONS` handler in `HttpService.kt`:

```kotlin
response.addHeader("Access-Control-Allow-Origin", "*")
response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
response.addHeader("Access-Control-Allow-Headers", "Content-Type")
// Access-Control-Allow-Private-Network: true  ← NOT SET
```

Even if paths and JSON shapes were fixed, Chrome may **silently drop** the HTTPS→HTTP request without this header.

---

### Finding 4: Model Name Provider Returns Placeholder String

```kotlin
// HttpService.kt:94-97
private fun modelNameProvider(): String {
    return if (visionLMManager.isModelLoaded) "loaded" else "none"
}
```

The contract expects the **actual model name** (e.g., `"qwen2.5-vl-3b"`), not the string `"loaded"`. bp-app uses this to show the user which model is serving their OCR request.

---

## Summary of Root Causes

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         WHY BP-APP CAN'T SEE AMM                        │
├─────────────────────────────────────────────────────────────────────────┤
│ 1. AMM doesn't listen on /v1/status  →  bp-app gets 404                │
│ 2. AMM /status returns wrong JSON shape → bp-app can't parse it        │
│ 3. bp-app hasn't written the probe code yet → no request is sent       │
│ 4. Missing CORS header → Chrome would block it even if both above fixed│
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Fix Required (Two-Sided)

### Side A: AMM (`HttpService.kt`)

**Add `/v1/status` endpoint** that returns the contract-compliant JSON:

```kotlin
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
```

**Fix model name provider** to return actual model name:

```kotlin
private fun modelNameProvider(): String {
    // TODO: read from VisionLMManager or SharedPreferences
    return if (visionLMManager.isModelLoaded) "qwen2.5-vl-3b" else "none"
}
```

**Add `/v1/vision/completions` endpoint** (alias to existing `/vision`):

```kotlin
(uri == "/v1/vision/completions" || uri == "/vision") && method == Method.POST -> {
    handleVisionRequest(session)
}
```

**Fix CORS preflight** to include PNA header:

```kotlin
response.addHeader("Access-Control-Allow-Private-Network", "true")
```

### Side B: bp-app (`app.js`)

Add probe logic on app startup:

```javascript
async function probeAMM() {
  try {
    const controller = new AbortController();
    setTimeout(() => controller.abort(), 3000);
    const res = await fetch('http://127.0.0.1:8765/v1/status', {
      signal: controller.signal,
      mode: 'cors'
    });
    if (!res.ok) return null;
    const data = await res.json();
    if (data.ready && data.capabilities?.includes('vision')) {
      return data;
    }
  } catch (e) {
    // AMM not installed or not running
  }
  return null;
}
```

And add AMM to the engine ladder:

```javascript
const engines = [];
const amm = await probeAMM();
if (amm) engines.push({ name: 'AMM', ...amm });
// ... then Ollama, OpenAI, template matcher
```

---

## Quick Test (No Code Changes)

To verify the HTTP service is reachable **before** fixing either side:

1. Ensure AMM HTTP service is running (Vision Hub → toggle ON)
2. On the same Android device, open Chrome
3. Navigate to `https://comfac-global-group.github.io/bp-app/`
4. Open Chrome DevTools (via `chrome://inspect#devices` on a PC with USB debugging)
5. In Console, run:

```javascript
fetch('http://127.0.0.1:8765/health')
  .then(r => r.json())
  .then(console.log)
  .catch(console.error);
```

**Expected today:** `"ok"` response (proves network path works).  
**Then test:**

```javascript
fetch('http://127.0.0.1:8765/v1/status')
  .then(r => { console.log('status:', r.status); return r.text(); })
  .then(console.log)
  .catch(console.error);
```

**Expected today:** `404` (proves the gap). After fixing AMM, this should return `200` with the contract JSON.

---

## Recommended Priority

| Fix | Side | Effort | Impact |
|-----|------|--------|--------|
| Add `/v1/status` + fix CORS | AMM | 30 min | Unblocks detection |
| Add `/v1/vision/completions` alias | AMM | 10 min | Unblocks inference |
| Implement `probeAMM()` in bp-app | bp-app | 1 hour | Completes integration |
| Add `Access-Control-Allow-Private-Network` | AMM | 5 min | Chrome compatibility |

---

## QA Verification Steps (After Fix)

1. Install AMM, download Qwen2.5-VL-3B, load in Vision Hub, start HTTP service
2. Open bp-app in Chrome Android
3. **Expected:** Settings shows green "AMM detected" pill
4. Take a BP monitor photo
5. **Expected:** bp-app auto-sends to AMM; AMM returns JSON with SYS/DIA/PULSE
6. **Expected:** bp-app validates ranges and shows green ticks
