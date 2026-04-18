# Vision Model Support — Functional Design Requirements

**Repo:** `android-matrix-model` (fork of SmolChat-Android)  
**Upstream:** `shubham0204/SmolChat-Android`  
**Date:** 2026-04-18  
**Status:** Open — analysis complete, implementation not started  
**Related:** `bp-app` blood pressure OCR PWA (GitHub Pages deployment)

---

## 1. Executive Summary

**Can SmolChat-Android run vision models?** **Yes — but not today.**

The `llama.cpp` submodule (pinned to `c08d28d`, 2026-04-05) **already contains** `libmtmd` — the official multimodal library supporting Gemma 4V, Qwen2.5-VL, SmolVLM, LLaVA, MiniCPM-V, and 15+ other vision architectures. However, the Android app currently:

- Does **not compile** any `mtmd` code in CMake
- Does **not expose** image input in the JNI bridge (`smollm.cpp`)
- Does **not provide** image APIs in the Kotlin layer (`SmolLM.kt`)
- Does **not have** image selection UI in Compose
- Has an **empty** `llama.cpp/` working directory (submodule not initialized)

This FDR analyzes all challenges, evaluates three strategies, and recommends a phased implementation path.

---

## 2. Current State

### 2.1 Submodule Reality Check

```bash
$ git submodule status
-c08d28d08871715fd68accffaeeb76ddcaede658 llama.cpp
# ^ leading dash = submodule NOT initialized in working tree

$ ls llama.cpp/
# EMPTY — no source files present
```

**Implication:** The repo cannot even build text-only inference right now without `git submodule update --init --recursive`. The CMake build references `../../../../llama.cpp` which resolves to an empty directory.

### 2.2 Current Build System

`smollm/src/main/cpp/CMakeLists.txt` builds:
- `LLMInference.cpp` — thin C++ wrapper around llama.cpp C API
- `smollm.cpp` — JNI glue
- `GGUFReader.cpp` — GGUF metadata reader

Links against: `llama`, `common`, `vulkan`  
**Does NOT link:** `mtmd`, `clip`

### 2.3 Current JNI Surface

```cpp
// smollm.cpp — existing JNI methods
Java_io_shubham0204_smollm_SmolLM_loadModel(...)
Java_io_shubham0204_smollm_SmolLM_addChatMessage(...)
Java_io_shubham0204_smollm_SmolLM_startCompletion(...)
Java_io_shubham0204_smollm_SmolLM_completionLoop(...)
// ... no image methods
```

### 2.4 Current Kotlin API

```kotlin
class SmolLM {
    suspend fun load(modelPath: String, params: InferenceParams)
    fun addUserMessage(message: String)
    fun getResponseAsFlow(query: String): Flow<String>
    // ... no image methods
}
```

### 2.5 Current UI

Jetpack Compose chat screen with:
- Text input field
- Model parameter sliders (temperature, min-p)
- Markdown rendering
- **No image picker, no camera capture, no image preview**

---

## 3. What llama.cpp Already Provides (libmtmd)

The submodule at `c08d28d` includes a **full production multimodal stack**:

### 3.1 libmtmd Architecture

```
┌─────────────────────────────────────────┐
│  mtmd-cli / mtmd-server / your JNI      │
├─────────────────────────────────────────┤
│  libmtmd (C++ library)                  │
│  ├── mtmd_tokenize() — text + images    │
│  ├── mtmd_bitmap_init() — RGB input     │
│  ├── mtmd_encode() — vision encoder     │
│  └── clip.cpp — ViT backbone            │
├─────────────────────────────────────────┤
│  Model-specific adapters                │
│  ├── gemma4v.cpp  (Gemma 4 Vision)      │
│  ├── qwen2vl.cpp  (Qwen 2.5 VL)         │
│  ├── qwen3vl.cpp  (Qwen 3 VL)           │
│  ├── llava.cpp    (LLaVA 1.5+)          │
│  ├── siglip.cpp   (SigLIP encoder)      │
│  ├── minicpmv.cpp (MiniCPM-V)           │
│  ├── pixtral.cpp  (Mistral Pixtral)     │
│  └── ... 15 total model files           │
├─────────────────────────────────────────┤
│  libllama (existing text LLM)           │
└─────────────────────────────────────────┘
```

### 3.2 C API for Images

```c
// Load vision projector alongside text model
mtmd_context* mtmd_init_from_file(
    const char* mmproj_fname,        // <-- NEW: path to .mmproj.gguf
    const llama_model* text_model,
    const mtmd_context_params params
);

// Create bitmap from RGB data (Android Bitmap -> native)
mtmd_bitmap* mtmd_bitmap_init(
    uint32_t nx, uint32_t ny,
    const unsigned char* data        // RGBRGBRGB... format
);

// Tokenize mixed text + images
int32_t mtmd_tokenize(
    mtmd_context* ctx,
    mtmd_input_chunks* output,
    const mtmd_input_text* text,     // prompt with <__media__> markers
    const mtmd_bitmap** bitmaps,     // array of images
    size_t n_bitmaps
);
```

### 3.3 Supported Vision Models (for BP Monitor OCR)

| Model | Size | OCR Quality | Speed | Notes |
|-------|------|-------------|-------|-------|
| **Qwen2.5-VL 3B** | ~3B + mmproj | ⭐⭐⭐ Excellent | Fast | Best small vision model for OCR |
| **SmolVLM 256M/500M** | ~0.5B + mmproj | ⭐⭐⭐ Good | Very Fast | HuggingFace's tiny vision model |
| **Gemma 4 4B-it** | ~4B + mmproj | ⭐⭐⭐⭐ Excellent | Medium | Google's latest, very capable |
| **MiniCPM-V 2.6** | ~8B + mmproj | ⭐⭐⭐⭐ Excellent | Medium | Strong on Chinese + English OCR |
| **LLaVA-Phi-3** | ~3.8B + mmproj | ⭐⭐ Good | Fast | Older, simpler architecture |

**Recommended for BP OCR:** **Qwen2.5-VL 3B** or **SmolVLM 256M** — smallest, fastest, excellent OCR.

### 3.4 Model File Requirements

Vision models need **TWO GGUF files**:
1. `model-text.gguf` — the language model (same as text-only)
2. `model-mmproj.gguf` — the vision encoder/projector

Example for Qwen2.5-VL 3B:
```
qwen2.5-vl-3b-instruct-q4_k_m.gguf    ← text model (~1.8 GB)
qwen2.5-vl-3b-instruct-mmproj-f16.gguf ← vision projector (~400 MB)
```

---

## 4. Challenge Analysis (Layer by Layer)

### 4.1 Layer 1: Build System / CMake

| Challenge | Severity | Details |
|-----------|----------|---------|
| Submodule uninitialized | 🔴 Blocker | `llama.cpp/` is empty; cannot build anything |
| mtmd not in CMake | 🔴 Blocker | `CMakeLists.txt` only builds `LLMInference.cpp`, `smollm.cpp`, `GGUFReader.cpp` |
| mtmd depends on `stb_image` | 🟡 Medium | `clip.cpp` includes `stb_image.h` for image decoding; need to ensure it's in include path |
| mtmd needs C++17 | 🟢 Low | Already using C++17 for main build |
| APK size increase | 🟡 Medium | Adding `libmtmd.so` + model files increases APK by ~200-500 MB |

**Fix:** Add `add_subdirectory(../../tools/mtmd mtmd)` to CMake, link `mtmd` to JNI library.

### 4.2 Layer 2: C++ JNI Bridge (smollm.cpp)

| Challenge | Severity | Details |
|-----------|----------|---------|
| No image JNI methods | 🔴 Blocker | Need `loadVisionModel()`, `addImageMessage()`, `tokenizeWithImages()` |
| Bitmap conversion | 🟡 Medium | Android `Bitmap` → JNI `jbyteArray` → `mtmd_bitmap_init()` with RGB data |
| Memory management | 🟡 Medium | `mtmd_bitmap_free()`, `mtmd_free()` must be called to avoid leaks |
| Threading | 🟡 Medium | `mtmd_tokenize()` is thread-safe per docs, but need to verify on Android |
| Mixed text+image batching | 🟡 Medium | `mtmd_input_chunks` must be iterated and fed to `llama_decode()` one chunk at a time |

**Fix:** Extend `LLMInference.cpp` with vision methods; add JNI wrappers in `smollm.cpp`.

### 4.3 Layer 3: Kotlin API (SmolLM.kt)

| Challenge | Severity | Details |
|-----------|----------|---------|
| No image types | 🔴 Blocker | Need data classes for `VisionMessage`, `ImageInput` |
| API signature change | 🟡 Medium | `load()` needs optional `mmprojPath` parameter |
| Flow streaming | 🟢 Low | Can reuse existing `Flow<String>` for response streaming |
| Bitmap handling | 🟡 Medium | Need to pass Android `Bitmap` or `ByteArray` through JNI |

**Fix:** Add `loadVisionModel(textPath, mmprojPath)`, `addImage(image: Bitmap)`, `getResponse(query): Flow<String>`.

### 4.4 Layer 4: Android App UI

| Challenge | Severity | Details |
|-----------|----------|---------|
| No image picker | 🔴 Blocker | Need camera/gallery image selection in chat screen |
| Image preview | 🟡 Medium | Show thumbnail of attached image in chat bubble |
| Permission handling | 🟡 Medium | `READ_MEDIA_IMAGES`, `CAMERA` permissions for Android 10+ |
| Image preprocessing | 🟡 Medium | Resize large camera images (4080×3060) to model's expected resolution before JNI |

**Fix:** Add `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` for gallery, `ActivityResultContracts.TakePicture()` for camera.

### 4.5 Layer 5: Model Download / Management

| Challenge | Severity | Details |
|-----------|----------|---------|
| Two files per model | 🟡 Medium | HFModelsAPI needs to download both `.gguf` and `.mmproj` |
| Storage quota | 🟡 Medium | Vision models are 2-4 GB total; need storage warning |
| Model validation | 🟡 Medium | Verify both files exist before allowing chat start |
| Filter update | 🟢 Low | `HFModelsAPI` filter `"gguf,conversational"` may miss vision models; may need `"vision"` tag |

**Fix:** Extend `LLMModel` entity with `mmprojUrl` field; update download logic to fetch both files.

### 4.6 Layer 6: Connection to bp-app (Browser PWA)

| Challenge | Severity | Details |
|-----------|----------|---------|
| No IPC mechanism | 🔴 Blocker | bp-app (browser) cannot talk to SmolChat (native app) |
| Local HTTP server | 🟡 Medium | Could add NanoHTTPD/Ktor inside SmolChat Service; bp-app calls `http://localhost:PORT` |
| Custom URL scheme | 🟡 Medium | SmolChat registers intent filter; bp-app redirects to `smolchat://ocr?image=...` |
| Bluetooth/WiFi | 🟡 Medium | Over-engineered for same-device communication |

**Fix:** See Strategy C below for bp-app integration.

---

## 5. Strategy Evaluation

### Strategy A: Full Native Vision Integration
**Modify SmolChat-Android to fully support vision models within its own UI.**

```
[Camera/Gallery] → [Compose UI] → [SmolLM.kt] → [JNI] → [mtmd+llama.cpp]
```

| Pros | Cons |
|------|------|
| Self-contained; no external dependencies | Very high engineering effort (~4-6 weeks) |
| Best UX — seamless image chat | Must modify 4 architectural layers |
| Reuses existing model download infrastructure | APK size grows significantly |
| Can be upstreamed to SmolChat project | Breaking changes in libmtmd expected |

**Effort:** Very High  
**Risk:** High (libmtmd is experimental, API may break)  
**Best for:** Making SmolChat a true multimodal chat app

---

### Strategy B: Minimal Vision Service (HTTP API)
**Add a background Android Service with a local HTTP server that exposes vision inference.**

```
[bp-app PWA] → HTTP POST → [SmolChat Service :8080] → [mtmd+llama.cpp]
                                    ↑
[SmolChat UI] ──────────────────────┘ (same backend)
```

| Pros | Cons |
|------|------|
| bp-app can use it immediately via `fetch()` | Must run HTTP server in background |
| Minimal UI changes to SmolChat | Android Doze mode may kill background service |
| Single inference backend, dual frontends | CORS and security considerations |
| Can reuse Service for desktop sync (roadmap item) | Battery impact |

**Effort:** High  
**Risk:** Medium  
**Best for:** Connecting bp-app to on-device vision

**HTTP API Design:**
```http
POST /v1/vision/completions
Content-Type: application/json

{
  "model": "qwen2.5-vl-3b",
  "image": "<base64-jpg>",
  "prompt": "Read the blood pressure monitor. Reply with SYS, DIA, PULSE only.",
  "temperature": 0.1,
  "max_tokens": 50
}

Response:
{
  "text": "SYS: 118, DIA: 78, PULSE: 59",
  "tokens_per_sec": 12.5
}
```

---

### Strategy C: Standalone Vision APK (OCR-Only)
**Create a minimal new Android app (`bp-vision-service`) that only does OCR, exposing an HTTP API.**

```
[bp-app PWA] → HTTP POST → [bp-vision-service :8080]
                                    ↓
                          [mtmd+llama.cpp with Qwen2.5-VL]
```

| Pros | Cons |
|------|------|
| No coupling to SmolChat codebase | Another app to maintain |
| Smallest APK (~50 MB for SmolVLM 256M) | User must install two apps |
| Purpose-built for OCR — no chat UI bloat | No reuse of SmolChat model management |
| Fastest to implement (~1-2 weeks) | Discovery/install friction for users |

**Effort:** Medium  
**Risk:** Low  
**Best for:** Quick path to on-device vision OCR for bp-app

---

### Strategy D: Web-Based Vision (No Android Changes)
**Don't use SmolChat at all. Run a vision model in the browser via ONNX/WebLLM.**

```
[bp-app PWA] → [ONNX Runtime Web / WebLLM] → [Qwen-VL in browser]
```

| Pros | Cons |
|------|------|
| Zero Android development | Model sizes (1-4 GB) exceed browser storage limits |
| Single codebase | Very slow inference in WASM/WebGL |
| No installation friction | No mobile GPU acceleration (WebGPU limited) |

**Effort:** Medium (research)  
**Risk:** High (browser constraints)  
**Best for:** Not recommended for mobile OCR

---

## 6. Recommended Strategy: Hybrid B → C

### Phase 1 (1-2 weeks): Standalone Vision Service APK (Strategy C)

Build `bp-vision-service` — a minimal Android app that:
1. Bundles **SmolVLM 256M** or **Qwen2.5-VL 3B** (text + mmproj GGUFs)
2. Exposes **NanoHTTPD** on `localhost:8765`
3. Accepts `POST /ocr` with base64 image
4. Returns JSON with `sys`, `dia`, `pulse`
5. Has **no UI** — runs as a foreground service with notification

**Why start here:**
- Fastest path to working on-device vision OCR
- Proves the mtmd → JNI → Android pipeline works
- Can be tested independently of bp-app and SmolChat

### Phase 2 (2-4 weeks): Integrate into SmolChat (Strategy A)

Once mtmd integration is proven:
1. Merge the JNI/C++ changes into SmolChat's `smollm` module
2. Add image picker to chat UI
3. Add vision model download from HuggingFace
4. Deprecate standalone `bp-vision-service`

### Phase 3 (1 week): bp-app Integration (Strategy B)

1. Add `fetch('http://localhost:8765/ocr', ...)` fallback to bp-app
2. Auto-detect if vision service is running
3. Graceful degradation to rotate90 + ocrad.js if service unavailable

---

## 7. Model Recommendation for BP Monitor OCR

### Primary: **Qwen2.5-VL 3B Instruct (Q4_K_M)**

| Spec | Value |
|------|-------|
| Text model | ~1.8 GB (Q4_K_M) |
| mmproj | ~400 MB (F16) |
| Total | ~2.2 GB |
| Context | 32K tokens |
| Strength | Excellent OCR, multilingual, follows instructions precisely |
| Quantization | Q4_K_M gives ~95% of F16 quality |

**Prompt for BP OCR:**
```
Read the blood pressure monitor display carefully.
The top number is systolic (SYS), middle is diastolic (DIA), bottom is pulse (PULSE).
These are 7-segment LCD digits. Pay attention to thin strokes.
Reply with ONLY the three numbers in this exact format:
SYS: <number>, DIA: <number>, PULSE: <number>
```

### Alternative: **SmolVLM 256M (Q8_0)**

| Spec | Value |
|------|-------|
| Text model | ~280 MB |
| mmproj | ~120 MB |
| Total | ~400 MB |
| Strength | Extremely fast, good enough OCR for clear images |
| Trade-off | Lower accuracy on poor lighting / glare |

---

## 8. Implementation Checklist

### Phase 1: bp-vision-service (Standalone)

- [ ] Initialize `llama.cpp` submodule (`git submodule update --init --recursive`)
- [ ] Create new module `bp-vision/` with CMake linking `mtmd`
- [ ] Write `VisionInference.cpp` — C++ wrapper around mtmd C API
- [ ] Write `vision_jni.cpp` — JNI bridge for bitmap → mtmd_bitmap
- [ ] Write `VisionService.kt` — foreground Android service
- [ ] Integrate NanoHTTPD (or Ktor) for HTTP endpoint
- [ ] Bundle SmolVLM 256M GGUF + mmproj as assets
- [ ] Add `POST /ocr` endpoint with base64 decode + inference
- [ ] Parse response with regex to extract SYS/DIA/PULSE
- [ ] Add battery optimization exemption prompt
- [ ] Test on Android 12+ with real BP monitor photos

### Phase 2: SmolChat Integration

- [ ] Extend `smollm/CMakeLists.txt` to build `mtmd` targets
- [ ] Add `loadVisionModel(textPath, mmprojPath)` to `LLMInference.cpp`
- [ ] Add `addImage(bitmap)` JNI method
- [ ] Add `tokenizeWithImages()` that handles `mtmd_input_chunks`
- [ ] Extend `SmolLM.kt` with image API
- [ ] Add image picker to `ChatActivity` Compose UI
- [ ] Update `HFModelsAPI` to detect and download `.mmproj` files
- [ ] Update `LLMModel` Room entity with `mmprojUrl` field
- [ ] Add vision model filter/tag to HF search
- [ ] Add image preview in chat bubbles

### Phase 3: bp-app Connection

- [ ] Add `fetch()` fallback in bp-app `runOCR()` function
- [ ] Probe `http://localhost:8765/health` on app startup
- [ ] If vision service available, send base64 image + prompt
- [ ] Parse JSON response and populate SYS/DIA/PULSE fields
- [ ] Show "Powered by on-device AI" badge when using vision service
- [ ] Fallback to rotate90 + ocrad.js if service unavailable

---

## 9. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| libmtmd API breaks in future llama.cpp sync | High | High | Pin submodule to known-good commit; fork llama.cpp if needed |
| Vision model too slow on mid-range Android | Medium | High | Start with SmolVLM 256M; offer Qwen2.5-VL as premium option |
| Memory OOM on 4GB RAM devices | Medium | High | Use `mmap` for model loading; add memory check before loading |
| Background service killed by Android | Medium | High | Use foreground service with persistent notification; document battery settings |
| CORS blocked in browser PWA | Low | Medium | Add `Access-Control-Allow-Origin: *` to HTTP responses |
| Model quantization reduces OCR accuracy | Medium | Medium | Test Q4_K_M vs Q8_0; allow user to download higher quality |
| Google Play rejects app with HTTP server | Low | Medium | Use localhost-only binding; document security in privacy policy |

---

## 10. Appendix: Key Files & References

### llama.cpp mtmd docs (in submodule)
- `tools/mtmd/README.md` — multimodal overview
- `tools/mtmd/mtmd.h` — C API header
- `tools/mtmd/mtmd-cli.cpp` — reference implementation
- `docs/multimodal.md` — supported models list
- `docs/multimodal/gemma3.md` — Gemma 3 vision guide
- `docs/multimodal/qwen2_vl.md` — Qwen2-VL guide

### Android references
- `docs/integrating_smollm.md` — consuming `smollm.aar` externally
- `docs/build_arm_flags.md` — CPU extension matrix
- `smollm/src/main/cpp/CMakeLists.txt` — current native build
- `smollm/src/main/java/io/shubham0204/smollm/SmolLM.kt` — Kotlin API

### HuggingFace model hubs
- https://huggingface.co/collections/Qwen/qwen2-vl-66cee7455501d312694b0ec5
- https://huggingface.co/collections/HuggingFaceTB/smolvlm-256m-and-500m-6740bd5841c52bca3af8408d
- https://huggingface.co/collections/google/gemma-3-release-67c6c6f89c4f76621268bb6d

---

## 11. Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-04-18 | Documented vision FDR | bp-app needs on-device vision; SmolChat has the hardware (mtmd in submodule) but not the wiring |
| 2026-04-18 | Recommended Phase 1 = standalone service | Fastest path to validation; decouples from SmolChat UI complexity |
| 2026-04-18 | Recommended model = Qwen2.5-VL 3B | Best accuracy/size trade-off for OCR; proven 7-segment LCD reading capability |
