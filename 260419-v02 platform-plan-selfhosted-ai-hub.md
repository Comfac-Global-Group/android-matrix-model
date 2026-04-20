# 260420-v03 — AMM Platform Plan (Android Matrix Models)

**Date:** 2026-04-20
**Status:** 🟡 In Progress — native build + HTTP service implemented, awaiting device testing
**Owner:** Justin
**Scope:** `android-matrix-model` → rebranded as **AMM** (Android Matrix Models)
**Consumers (v1):** `bp-app` (blood pressure OCR PWA) + future Comfac-IT PWAs
**Companion docs:**
- `vision-models-fdr.md` — original on-device VLM integration analysis (layer-by-layer)
- `vision-models-fdr-REVIEW.md` — critique
- `260419-v01 vision-OCR-new-direction.md` — "measure first" pivot for the BP-OCR slice
- `bp-app/BP-FRD.md` — consumer-side requirements
- `repoanalysis.md` — current codebase map

### Implementation Progress (2026-04-20)

| Layer | Status | Notes |
|---|---|---|
| CMake + libmtmd build | ✅ Done | `mtmd` linked, `stb_image` included, C++17 enabled |
| VisionInference C++ wrapper | ✅ Done | `loadModel`, `loadImage`, `startCompletion`, `completionLoop` |
| JNI bridge (smollm.cpp) | ✅ Done | 9 new JNI methods for vision |
| Kotlin API (SmolLM.kt) | ✅ Done | `loadVisionModel`, `loadVisionImage`, `getVisionResponse` |
| VisionLMManager | ✅ Done | Koin singleton, suspend load/infer/unload |
| HTTP Service (NanoHTTPD) | ✅ Done | `GET /health`, `GET /status`, `POST /vision` with CORS |
| VisionHubActivity (UI) | ✅ Done | Compose screen with service toggle + model load controls |
| Drawer navigation | ✅ Done | "Vision Hub" added to chat drawer |
| Rebrand (AMM) | ✅ Done | App name, release asset name, theme, strings |
| Model download (vision pairs) | 🟡 Pending | File-based heuristic for MVP; formal DB schema later |
| Device benchmark (PNA + accuracy) | 🔴 Blocked | Requires real Android device + Qwen 3.5 4B model |
| GitHub Release v1.0.0-amm | 🔴 Pending | Tag + APK upload after device validation |

---

## 1. One-Line Summary

> **`android-matrix-model` is a sideload-installed, self-hosted on-device AI appliance. Other apps talk to it over a local HTTP API to run vision, speech, and document-OCR workloads without leaving the phone.**

---

## 2. Why This Plan Exists

The existing docs answer two narrow questions:

1. *Can we run a vision LLM on Android?* → `vision-models-fdr.md` (yes, via `libmtmd`, ~4–6 weeks of native work)
2. *Do we even need a VLM for BP OCR?* → `260419-v01` (measure Tesseract/MediaPipe/Gemini first)

Neither doc frames the **broader architecture** the user has described:

- `android-matrix-model` isn't just "SmolChat with a vision feature". It is a **local AI hub** — one installed APK that many other apps (bp-app first, then others) call into over a loopback API.
- The distribution model matters: a **signed APK in GitHub Releases**, installed via **sideload** (no Play Store gatekeeping, no cloud dependencies).
- The install-time consent is **one-shot** — after the user approves, all on-device capabilities (vision, ASR, document OCR/indexing) are activated without per-feature re-approval.
- The BP-OCR pivot (cheap engines first) is still valid, but sits **inside** this platform rather than replacing it. If a cloud-free path is required, the platform is the fallback.

This doc establishes that platform framing so future capability work (ASR, document indexing) has a plan to slot into.

---

## 3. Product Frame

### 3.1 What the user installs

A single APK named **`matrix-model-<version>-<arch>.apk`**, published on **GitHub Releases** under `Comfac-Global-Group/android-matrix-model` (or current org). Users get it via:

| Channel | Audience |
|---|---|
| Direct download from Releases | Power users; one-tap install once "Install unknown apps" is granted to the browser |
| [Obtainium](https://obtainium.imranr.dev/) | Users who want OTA-style update notifications without Play Store |
| F-Droid (future) | Eventual listing — requires reproducible builds + FOSS manifest |

**Not shipped via Play Store** (initially). Rationale: Play rejects APKs that bundle LLM weights (size, inference of foreign code), and a localhost HTTP server can trip Play security review. Sideload keeps the platform honest to its "self-hosted" promise.

### 3.2 What "activate" means

On first launch, the app presents a single consent screen:

> "Matrix Model runs small AI models on this device. To let your other apps (BP Log, document tools, voice notes) use them, we need to:
> - Run a background service that exposes a **localhost-only** API on this phone
> - Store ~0.5–4 GB of model files in app-private storage
> - Access camera / microphone / files only when you trigger an AI task from another app
> - Keep the inference service alive while you're using it
>
> [**Activate All**] [Configure per-capability...]"

"Activate All" flips every capability to on and schedules the default model bundles for download. Per-capability configuration lets the user disable or swap models. No telemetry, no external network except for optional model downloads from HuggingFace (explicit, listed URLs only).

### 3.3 What other apps see

A **Matrix Model client SDK** shipped as:
1. A tiny JS module (`@gi7b/matrix-client` — ~5 KB) for PWAs like bp-app.
2. An Android library (`matrix-client-android` — AAR) for native Android clients.
3. Plain HTTP/JSON for anything else (Python scripts, Node daemons).

All three hit the same local endpoint: `http://127.0.0.1:8765/v1/…`.

---

## 4. Capability Catalogue (v1 → v3)

### 4.1 v1 — Vision / Image Recognition *(🟢 priority)*

**Endpoint:** `POST /v1/vision/completions`

**Use cases:**
- **BP Log** — send BP-monitor photo + "read the SYS/DIA/PULSE digits" prompt → structured JSON back.
- **Generic image captioning** — any app asks "what is in this image?".
- **Receipt / form reading** — paired with v3 document indexing.

**Models (tiered):**

| Tier | Model | Size | Use case |
|---|---|---|---|
| **Small** (default) | **Qwen 3.5 4B Instruct** (Q4_K_M) | ~3.4 GB | Best size/accuracy trade-off for 7-seg LCD; requires rotate90 preprocessing |
| **Medium** | Qwen2.5-VL 3B (Q4_K_M) | ~2.2 GB | Best general-purpose OCR + reasoning; validate on 7-seg before committing |
| **Large** (optional) | Gemma 4 4B-it | ~2.5 GB | Best multilingual; 7.2 GB full model, perfect accuracy with rotate90 |

**Note on SmolVLM 256M:** This was originally considered as the ~400 MB default, but experimental evidence shows every sub-1 GB vision model (MedGemma 3.3 GB at 31%, Qwen 0.8B at 24%) performs poorly on 7-segment LCDs. SmolVLM 256M must be validated on the BP sample set before it can be declared viable. If it fails, Qwen 3.5 4B (~3.4 GB) is the smallest model with proven full-match capability.

User picks one at activation; can swap later from Settings. APK itself ships empty — **models are downloaded on first use**, not bundled. This keeps the APK under 100 MB for the Play Store fallback option and for fast sideload.

**Engine:** `llama.cpp` submodule's `libmtmd` (already present at `c08d28d`). See `vision-models-fdr.md` §3 for the C API details.

**AMM stays local-only.** AMM does not proxy to cloud APIs. Clients like bp-app that want to use Ollama or an OpenAI-compatible API call those endpoints directly in their own engine-priority list. AMM's only job is on-device inference. This keeps the APK's security surface small and its identity clear: if AMM is installed, inference happens on the phone.

**Prompt contract:** callers send `prompt` + `image` + optional `response_schema` (JSON schema). When a schema is given, the service runs a single retry pass if the output fails to parse, then returns raw text with an error flag. The caller (e.g., bp-app) may override the default prompt entirely — prompts are caller-provided, not hard-coded in AMM.

### 4.2 v2 — Audio → Text (ASR + Export) *(🟠 secondary)*

**Endpoint:** `POST /v1/audio/transcriptions`

**Use cases:**
- Voice notes in any PWA (send `audio/webm` or `audio/wav`, get transcript back).
- Meeting recordings → searchable text stored in the document index (v3).
- Accessibility: live captions in apps that pipe microphone audio through the local API.

**Models:**

| Tier | Model | Size | Notes |
|---|---|---|---|
| **Small** (default) | whisper.cpp `ggml-tiny.en` (Q5_0) | ~40 MB | En-only, streaming |
| **Medium** | whisper.cpp `ggml-base` (Q5_0) | ~80 MB | Multilingual |
| **Large** | whisper.cpp `ggml-small` (Q5_0) | ~240 MB | Better punctuation |

**Engine:** `whisper.cpp` (sibling of llama.cpp from the ggml ecosystem). Compile paths are well-trodden on NDK — the same CMake patterns the vision FDR uses apply.

**Export formats:** plain text, `.srt` (timestamped), `.vtt`, JSON with word-level timestamps.

### 4.3 v3 — Document Indexing & OCR (Paperless-style) *(🟡 later)*

**Endpoint group:**
- `POST /v1/documents/ingest` — push a PDF/image/.txt → store, OCR if needed, embed, index
- `GET  /v1/documents/search?q=…` — vector + FTS search over the local corpus
- `GET  /v1/documents/{id}` — fetch metadata + OCR text
- `GET  /v1/documents/{id}/content` — fetch the original blob

**Use cases:**
- Paperless-NGX-style personal document vault, but running **on the phone**, not a home server.
- Any app can ask "find me the document that mentioned X" without pushing data to cloud search.
- BP Log can optionally push its monthly PDF reports into the index for later lookup.

**Stack:**

| Layer | Choice | Rationale |
|---|---|---|
| OCR | tesseract + `letsgodigital` + fallback to v1 VLM | Cheap engines first; VLM only for hard pages |
| Embeddings | `all-MiniLM-L6-v2` GGUF or ONNX (<100 MB) | Already referenced in SmolChat's related projects (`Sentence-Embeddings-Android`) |
| Vector store | **`smolvectordb`** — already in this repo | Reuse instead of bundling sqlite-vec |
| FTS | SQLite FTS5 | Stock Android sqlite supports it |
| Storage | App-private directory + SAF-exported export bundles | No external-storage permission needed |

**Privacy invariant:** the document index **never** leaves the device except via explicit user-triggered export (SAF file picker → user picks destination).

### 4.4 Future capabilities (post-v3)

- **Translation** — same whisper/llama stack with translation prompts, or dedicated NLLB GGUF.
- **Code assistant** — a small code-tuned SLM for local dev helpers (likely wraps existing SmolChat functionality).
- **Structured extraction** — generic JSON-schema-guided extraction over arbitrary inputs.

---

## 4.5 Future Roadmap (AMM Capabilities Beyond BP-OCR)

While v1 focuses on blood-pressure monitor OCR for bp-app, the AMM platform architecture is designed to absorb additional capabilities without breaking the API contract. The following are **planned but not scheduled** — they enter the roadmap as client apps demand them or as developer bandwidth allows.

### 4.5.1 Transcription (ASR) — v2
Already catalogued in §4.2. Real-world use cases:
- Voice memos in any PWA → searchable text.
- Live captioning during video calls or meetings.
- Accessibility: real-time captions for hard-of-hearing users.

### 4.5.2 General-Purpose OCR — v2/v3
Beyond BP monitors:
- Receipt reading → structured `{merchant, date, total, items[]}`.
- Form field extraction → fill PDFs from photographed documents.
- Handwritten note transcription (fallback to VLM when Tesseract fails).

### 4.5.3 File-Type Conversion — v3
Local document transformers:
- **Image → PDF** — batch photos → single PDF with OCR text layer.
- **PDF → text/Markdown** — extract and reformat document content.
- **JSON ↔ CSV / YAML** — lightweight data-format conversion without leaving the device.
- **Spreadsheet → JSON** — parse photographed tables into structured data.

### 4.5.4 Object Recognition & Camera Streaming — v3/v4
Continuous or triggered visual analysis:
- **Real-time object detection** — YOLO / MobileNet running via NNAPI, streaming detection logs.
- **Camera log mode** — app holds camera open, logs every recognised object with timestamp + confidence to a local SQLite table; exportable as CSV.
- **Barcode / QR scanning** — fast native path without ZXing dependency.
- **Scene captioning** — describe surroundings for accessibility or inventory.

### 4.5.5 Document Indexing & Search — v3
Full personal-document vault (see §4.3):
- Ingest PDFs, images, text files → OCR → embeddings → vector + FTS index.
- Semantic search: "find the receipt from the restaurant last month".
- Export/import of the entire index as an encrypted bundle.

### 4.5.6 Integration Philosophy

All future capabilities follow the same rules established in v1:
1. **Single localhost API** — clients discover capabilities via `GET /v1/status` and call the relevant endpoint.
2. **Models downloaded on demand** — the APK stays small; users activate only what they need.
3. **Offline by default** — once models are cached, no network is required.
4. **Privacy-first** — data never leaves the device except via explicit user-triggered export.
5. **Caller-provided prompts** — where a prompt is involved (VLMs, structured extraction), the caller supplies it; AMM does not hard-code domain logic.

---

## 5. IPC / API Design

### 5.1 Transport

**Primary:** localhost HTTP/JSON on `127.0.0.1:8765` (configurable), served from a **foreground Android service** with a persistent notification. This is the lowest-friction path for PWAs (browsers can `fetch('http://127.0.0.1:8765/…')`).

**Secondary (Android-to-Android):** `AIDL` interface on a bound service, avoiding the HTTP serialisation cost. Same command surface, binary payload path.

**Tertiary (content-provider):** a read-only `ContentProvider` (`content://io.gi7b.matrix/status`) so apps can detect "is Matrix Model installed?" without making a network call. Returns version, capabilities, ready-state.

### 5.2 Endpoint surface (v1 lock)

```
GET  /v1/status
     → { "version": "1.0.0",
         "capabilities": ["vision", "asr", "documents"],
         "models": { "vision": "smolvlm-256m", "asr": "whisper-tiny.en" },
         "ready": true }

POST /v1/vision/completions
     Body: { "image": "<base64-or-blob-ref>",
             "prompt": "...",
             "response_schema": { ... }?,   // optional JSON schema
             "max_tokens": 128,
             "temperature": 0.1 }
     → { "text": "...",
         "parsed": { ... }?,                // present if schema validated
         "usage": { "tokens": N, "ms": M } }

POST /v1/audio/transcriptions
     Body: multipart/form-data with `audio` (file) + `format` ("text"|"srt"|"vtt"|"json")
     → { "text": "...", "segments": [...], "language": "en" }

POST /v1/documents/ingest
     Body: multipart with `file` + optional tags, title, date
     → { "id": "doc_...", "ocrPreview": "...", "indexedAt": "..." }

GET  /v1/documents/search?q=<query>&limit=20
     → { "results": [ { "id": "...", "score": 0.82, "title": "...", "snippet": "..." }, ... ] }
```

### 5.3 Authentication & security

**Default:** the service binds **only** to `127.0.0.1`. Any app on-device can hit it; no network exposure. This is the same trust boundary Android already grants to any installed app.

**Optional opt-in:** Matrix Model can issue a per-caller API key, stored in its own Android keystore, and require `Authorization: Bearer …` headers. v1 skips this; v2 adds it for apps that want to prove they called (audit trail).

**Browser access:** respond with `Access-Control-Allow-Origin: *` *only* for endpoints that don't leak cross-origin secrets (status, completions). Browsers can call from any PWA origin (including local file://). This is safe because the service is loopback-only — no internet attacker can reach it.

### 5.4 Lifecycle

- Service starts automatically after activation and on device boot (WorkManager + BootReceiver).
- Foreground service notification is required by Android 8+ for persistence; text reads "Matrix Model — ready".
- Going-to-sleep behaviour: service stays alive under the foreground-service exemption. No Doze-mode killing.
- Clients probe `GET /v1/status` on startup; if it fails, they fall back gracefully (see §6 bp-app integration).

---

## 6. Consumer Integration — bp-app as the Reference Client

### 6.1 Current state

`bp-app` ships a pure-JS on-device OCR (ocrad.js). The `260419-v01` pivot stacks Tesseract.js / MediaPipe / Gemini as alternate engines. All of these sit under a single `runOCR(image)` abstraction.

### 6.2 What changes

Add one more engine to that abstraction: **"Matrix Model (on-device VLM)"**.

```js
async function runOCRViaMatrix(imageBlob, prompt) {
  const form = new FormData();
  form.append('image', imageBlob);
  form.append('prompt', prompt);
  form.append('response_schema', JSON.stringify(BP_SCHEMA));
  const res = await fetch('http://127.0.0.1:8765/v1/vision/completions', {
    method: 'POST', body: form, signal: AbortSignal.timeout(15000)
  });
  if (!res.ok) throw new Error('matrix-model-unavailable');
  return await res.json();
}
```

### 6.3 Engine selection order (proposed)

bp-app checks engines in this order on each OCR run:

1. **Matrix Model (AMM)** — if `GET /v1/status` succeeded at app startup → use it (best accuracy, fully on-device).
2. **Local Ollama** — direct call to `localhost:11434`; if a vision model is loaded, use it.
3. **OpenAI-compatible API** — direct call to configured endpoint + key; used if online and configured.
4. **7-segment template matcher** — browser-based pure-JS fallback (~80% accuracy with user-drawn LCD ROI; zero network).
5. **Manual entry** — the user types the numbers if everything else fails.

The user controls the order in bp-app Settings (default: as above). **Matrix Model is never required** — bp-app remains fully functional without it.

### 6.4 Prompt shape for BP

```
Read the blood pressure monitor. Return JSON: {sys, dia, bpm}. No prose.
```

Experiments show **minimal prompts outperform verbose ones** for 7-segment LCD extraction. Descriptions of "thin strokes," "7-segment digits," or expected ranges do not improve accuracy and may confuse smaller models. The prompt is **caller-provided and editable** — bp-app ships the minimal default but lets users customise it per-photo.

**A/B test before locking:** Run the leaderboard (Qwen 3.5 4B, Gemma 4, MedGemma, Qwen 0.8B) with (a) the minimal prompt above and (b) a verbose range-describing prompt. Keep whichever wins.

Schema-guided decoding makes failure explicit: `response_schema` validation fails if the VLM emits prose instead of JSON, which lets bp-app retry or fall back without parsing fragile regex.

---

## 7. Distribution & Update Plan

### 7.1 Release flow

1. Tag `v0.X.Y` on `main`.
2. GitHub Action builds signed APKs for `arm64-v8a` and `armeabi-v7a` and a universal APK (larger).
3. Release notes list capability-level changes (e.g. "v0.3 — adds ASR endpoint").
4. Obtainium users get the notification automatically; direct-download users see the GitHub Release RSS / email.

### 7.2 First-time user path (target: ≤ 3 minutes)

```
(1) Visit Releases page on phone browser
(2) Tap matrix-model-<ver>-universal.apk
(3) Android prompts "Allow Chrome to install unknown apps?" → Allow
(4) Installer opens → Install → Open
(5) Consent screen → "Activate All"
(6) App downloads Qwen 3.5 4B Instruct (~3.4 GB over Wi-Fi)
(7) Ready. bp-app and other clients can now reach 127.0.0.1:8765.
```

### 7.3 Model updates

Models are decoupled from APK releases. The app fetches a **model manifest** (`https://raw.githubusercontent.com/<org>/<repo>/main/metadata/models.json`) which lists current model URLs + SHA256s. Users pull new model versions from Settings without re-installing the APK.

### 7.4 Offline mode

After models are cached, the app works fully offline. No network calls are required to serve requests. The only network egress is: (a) initial model download, (b) optional model-manifest refresh, (c) optional OTA update check (which can be disabled).

---

## 8. Phased Delivery

### Phase 0 — Go/No-Go Tests *(must pass before any AMM code is written)*

**Test 1 — Private Network Access / Mixed Content (plan-killer if red)**

bp-app is served from `https://bp.comfac-it.com`. AMM exposes `http://127.0.0.1:8765`. Chrome's Private Network Access (PNA) now requires a CORS preflight for HTTPS → loopback; Safari/iOS does not permit it at all.

**Procedure:**
1. Stub AMM as a 20-line Node server in Termux on a real phone (`http://127.0.0.1:8765`).
2. Open bp-app in Chrome and Safari from `https://bp.comfac-it.com`.
3. Attempt `fetch('http://127.0.0.1:8765/v1/status')`.

**Outcomes:**
- **Green (both browsers permit)** → proceed with loopback HTTP IPC as planned.
- **Red (blocked)** → pick a remediation *before* writing JNI code:
  - **(a)** Serve bp-app over plain HTTP (kills PWA install on iOS, degrades UX).
  - **(b)** Ship a self-signed or private-CA certificate inside AMM and serve HTTPS on loopback (significant lift: cert generation, trust installation, renewal).
  - **(c)** Abandon PWA → build a native Android bp-app that binds to AMM via AIDL/Intent (redraws the entire client architecture).

This is a **Phase 0 go/no-go**, not a footnote. One afternoon with a real phone answers whether the integration shape is viable.

**Test 2 — Baseline accuracy measurement**

Run the entire `Bloodpressure Samples/` set (7+ photos) through:
- (a) **ocrad.js pipeline** — expected 0 %; confirms traditional OCR is dead on 7-seg
- (b) **GPT-4o or GPT-4o-mini via API** (ceiling benchmark) — minimal prompt, rotate90
- (c) **Qwen 3.5 4B via Ollama** (target benchmark) — minimal prompt, rotate90
- (d) **7-segment template matcher** — user-drawn ROI, measure actual accuracy

Record full-match rate per engine. This gives the real numbers that Phase 1 exit criteria must be grounded in — not aspirational 90 % targets.

**Prompt A/B:** Run (b) and (c) with both (i) minimal prompt `"Read the monitor. Return JSON: {sys, dia, bpm}. No prose."` and (ii) verbose range-describing prompt. Document which wins.

**Test 3 — SmolVLM 256M validation**

Before declaring SmolVLM 256M the default small-tier model, validate it on the same BP sample set. If it fails to read 7-segment digits reliably, **Qwen 3.5 4B** remains the effective default, which changes the RAM requirements and 4GB-device story.

**Decision gate:** All three tests must complete with documented numbers before Phase 1 begins.

### Phase 1 — Vision API MVP (2–3 weeks)

- Initialise `llama.cpp` submodule and add `libmtmd` to CMake (`vision-models-fdr.md` §4.1).
- Implement `VisionInference.cpp` + JNI wrapper.
- Ship a **foreground service** with NanoHTTPD or Ktor exposing `POST /v1/vision/completions` on `127.0.0.1:8765`.
- Bundle download UI for **Qwen 3.5 4B Instruct** (default, ~3.4 GB) and **Qwen2.5-VL 3B** (opt-in, ~2.2 GB). SmolVLM 256M is held as a candidate only if Phase 0 Test 3 validates it on 7-seg digits.
- Activation consent screen.
- `GET /v1/status` for client handshake.
- Ship JS client SDK (`matrix-client.js`) in this repo's `resources/` for PWA consumers.
- bp-app: add Matrix Model to the engine selection order.

**Exit criteria:** bp-app on a phone with Matrix Model installed extracts SYS/DIA/PULSE from the full `Bloodpressure Samples/` set (7+ photos, expanding to 30+) with a full-match rate that meets or exceeds the **measured Qwen 3.5 4B + rotate90 benchmark** from Phase 0 Test 2. The target is grounded in measured reality, not aspiration. If the benchmark lands at 5/7, the exit criterion is 5/7 (or better), not 90 %.

### Phase 2 — ASR API (1–2 weeks)

- Add whisper.cpp submodule; compile with the same NDK toolchain.
- `AsrInference.cpp` + JNI.
- `POST /v1/audio/transcriptions`.
- Default `ggml-tiny.en`; let users download `base` / `small`.
- Export formats: text / SRT / VTT / JSON with timestamps.

**Exit criteria:** any PWA can upload a 30-second voice note and get a transcript back in ≤ 5 s on a mid-range 2024 Android.

### Phase 3 — Document Indexing (3–4 weeks)

- Reuse `smolvectordb` module already in this repo for vector storage.
- Tesseract for baseline OCR; fall back to Phase 1 VLM for hard pages.
- `POST /v1/documents/ingest`, `GET /v1/documents/search`.
- SAF-based export/import of the full document store.

**Exit criteria:** 100-document personal archive (receipts, manuals, letters) indexed in one session on-device, with full-text + semantic search returning results in ≤ 500 ms.

### Phase 4 — Polish & Federation

- Optional per-caller API keys.
- AIDL binding for native Android clients.
- F-Droid submission.
- Model swap UI with disk-usage preview.
- Optional desktop companion that shares the same API (via ADB reverse-tunnel or WiFi — matches the upstream SmolChat "future" item).

---

## 9. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `libmtmd` API churn in llama.cpp | High | High | Pin submodule; fork if necessary; abstract the C API behind `VisionInference.cpp` |
| APK rejected by Android system when sideloading on vendor-locked phones | Medium | High | Document steps for common OEMs (Xiaomi / Huawei); provide universal + per-arch APKs |
| OEM battery-management kills the foreground service | High | High | Add "battery optimization exemption" prompt on first run; document Don't Kill My App links |
| OOM on 4 GB devices when loading Qwen 3.5 4B | Medium | High | Gate on pre-flight `ActivityManager.getMemoryInfo()` check; if < 6 GB RAM, warn user and suggest direct Ollama or OpenAI API fallback in bp-app |
| Model download over cellular surprises users | Medium | Medium | Default to Wi-Fi-only downloads; explicit "allow on cellular" toggle |
| Browser blocks loopback fetch under HTTPS strict-origin (PWA served over HTTPS trying to call http://127.0.0.1) | High | High | **Phase 0 go/no-go test** — confirm on real devices before writing JNI. Remediations: (a) serve bp-app over HTTP (kills PWA install), (b) AMM serves HTTPS with self-signed cert (big lift), (c) abandon PWA for native Android client |
| Play Store rejects future listing | High | Low | Not in initial distribution plan; sideload-first |
| User can't find "Install unknown apps" setting | High | Low | Link step-by-step instructions per-OEM in README; show a QR to the installer help page in the app |
| Two client apps make concurrent heavy calls | Medium | Medium | Queue requests in the service; expose `queue_depth` in `/v1/status`; clients can back off |

---

## 10. bp-app Integration Contract

This section is the single source of truth for how `bp-app` (the blood-pressure PWA) integrates with AMM. It consolidates the handshake, request/response shape, fallback behaviour, and exit criteria that both teams must agree on before Phase 1 begins.

---

### 10.1 Role in the Ladder

AMM is the **first-priority engine** in bp-app's OCR ladder. The empirical baseline from `experiments/OCR_BENCHMARK_REPORT.md` justifies this ranking:

| Engine | Evidence | Full-Match Rate |
|--------|----------|-----------------|
| **Qwen 3.5 4B + rotate90** (AMM target) | 1/1 tested; pending full sweep | ~100% (projected) |
| **Gemma 4 + rotate90** | 4/4 on rotated variants | 100% (rotate90 only) |
| **MedGemma + contrast** | 13/42 across all variants | 31% |
| **Qwen 0.8B + original** | 10/42 across all variants | 24% |
| **7-seg template matcher** | ~80% with correct ROI | ~80% |
| **ocrad.js / Tesseract.js** | 0/11 and 0/11 | 0% |

AMM's job is to deliver **Qwen 3.5 4B-class accuracy** on-device, fully offline. If AMM is installed and ready, bp-app skips Ollama and OpenAI entirely.

---

### 10.2 GET /v1/status Handshake + Caching

**Probe on app startup:**

```js
const ammReady = await fetch('http://127.0.0.1:8765/v1/status', {
  signal: AbortSignal.timeout(2000)
}).then(r => r.ok).catch(() => false);
```

**Cached result:** `ammReady` is stored in a session variable (`window._ammStatus`). Re-probed only when:
- User explicitly taps "Refresh connection" in Settings
- App returns from background after > 5 minutes
- A previous AMM call timed out or returned 5xx

**UI consequence:** If `ammReady === true`, the OCR button shows a small **"🛡 Offline AI"** badge. If `false`, the badge is hidden and the engine ladder starts at #2 (Ollama).

---

### 10.3 PNA / Mixed-Content — Phase 0 Go/No-Go Gate

bp-app is served from `https://bp.comfac-it.com`. AMM exposes `http://127.0.0.1:8765`.

**Chrome:** Private Network Access (PNA) requires a CORS preflight (`Access-Control-Request-Private-Network: true`) for HTTPS → loopback. AMM must respond with `Access-Control-Allow-Private-Network: true`.

**Safari / iOS:** Does not permit HTTPS → loopback `fetch()` at all.

**Go/no-go test (mandatory before JNI code):**
1. Stub AMM as a 20-line Node server in Termux (`http://127.0.0.1:8765`).
2. Open bp-app in Chrome and Safari from `https://bp.comfac-it.com`.
3. Attempt `fetch('http://127.0.0.1:8765/v1/status')`.

**Green:** Both browsers permit → proceed.
**Red:** Pick remediation before writing code:
- (a) Serve bp-app over HTTP (kills PWA install on iOS)
- (b) Ship self-signed cert in AMM, serve HTTPS on loopback (big lift)
- (c) Abandon PWA → native Android bp-app via AIDL

This is not an open question; it is a **binary gate**.

---

### 10.4 Request/Response Contract for `/v1/vision/completions`

**Pre-processing is owned by bp-app**, not AMM. bp-app rotates, scales, and encodes the image before sending.

**Request:**

```http
POST /v1/vision/completions
Content-Type: application/json

{
  "image": "<base64-jpeg>",
  "prompt": "Read the blood pressure monitor. Return JSON: {sys, dia, bpm}. No prose.",
  "temperature": 0.1,
  "max_tokens": 256
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `image` | ✅ | JPEG, rotated 90° clockwise by bp-app, max 1920 px on long edge |
| `prompt` | ✅ | Caller-provided; AMM does not modify or append to it |
| `temperature` | ❌ | Default 0.1 if omitted |
| `max_tokens` | ❌ | Default 256 if omitted |

**Response (success):**

```json
{
  "text": "{\"sys\": 118, \"dia\": 78, \"bpm\": 59}",
  "usage": { "tokens": 42, "ms": 3200 }
}
```

**Response (failure):**

```json
{
  "error": "model_not_loaded",
  "message": "No vision model is currently loaded in AMM."
}
```

**Timeout:** bp-app uses `AbortSignal.timeout(15000)` (15 s). If AMM hasn't responded, bp-app falls back to the next engine in the ladder.

---

### 10.5 Caller-Provided Prompt

AMM **does not hard-code a BP prompt**. The prompt travels from bp-app to AMM unchanged.

**Default (minimal):**

```
Read the blood pressure monitor. Return JSON: {sys, dia, bpm}. No prose.
```

**Verbose opt-in:** Users can switch to a longer prompt in Settings if they find the minimal one unreliable for their device.

**User-editable:** Before sending, the user sees the pre-filled prompt in a collapsible panel and can edit it. Edits are remembered per-session.

**A/B test gate:** Before Phase 1 ships, run the 4-model leaderboard with (a) minimal and (b) verbose prompt. The winner becomes the default.

---

### 10.6 Client-Side Validation + Derived Confidence

AMM returns raw text. **Confidence is never model-reported.** bp-app derives it client-side:

| Check | Rule | Fail Action |
|-------|------|-------------|
| SYS range | 90–220 mmHg | Amber badge |
| DIA range | 50–130 mmHg | Amber badge |
| BPM range | 40–180 bpm | Amber badge |
| DIA < SYS | Always | Red badge |
| Pulse pressure | 20–100 mmHg (SYS − DIA) | Amber badge |

**Confidence badge:**
- **Green** — all checks pass
- **Amber** — one check fails
- **Red** — two+ checks fail or a value is null

This is more reliable than a 1–3 GB VLM self-assessing `"high|medium|low"`.

---

### 10.7 Fallback Ladder

bp-app tries engines in strict priority order, stopping at the first success:

```
┌─────────────────────────────────────────────────────────────┐
│  1. AMM (127.0.0.1:8765)                                    │
│     └─> rotate90 JPEG + minimal prompt                      │
│     └─> timeout / 5xx / parse fail → next                   │
│  2. Local Ollama (localhost:11434)                          │
│     └─> direct call, same rotate90 + prompt                 │
│     └─> fail → next                                         │
│  3. OpenAI-compatible API                                   │
│     └─> direct call, same rotate90 + prompt                 │
│     └─> fail or offline → next                              │
│  4. 7-Segment Template Matcher (browser JS)                 │
│     └─> "Tap the display to help us" screen                 │
│     └─> user draws LCD ROI, matcher runs                    │
│     └─> fail or user skips → next                           │
│  5. Manual Entry                                            │
│     └─> editable sys/dia/bpm fields, pre-filled with        │
│         any partial data extracted along the way            │
└─────────────────────────────────────────────────────────────┘
```

**ocrad.js is removed.** Citation: `experiments/OCR_BENCHMARK_REPORT.md` — 0/11 full-match across 843+ combinations. It is no longer in the bundle's engine ladder.

---

### 10.8 Settings UI Controls

| Control | Default | Behaviour |
|---------|---------|-----------|
| **Use VLM for OCR** master toggle | ON if AMM detected at startup, OFF otherwise | When OFF, ladder starts at #4 (template matcher) |
| **Engine priority** drag-list | AMM → Ollama → OpenAI → Template → Manual | User reorders; disabled engines are greyed out |
| **Default prompt** editor | Minimal prompt | One-tap reset to minimal or verbose; edits remembered per-session |
| **API key** (OpenAI only) | Empty | Stored in localStorage; honest disclaimer about trust boundary |
| **Auto-rotate 90°** | ON | bp-app rotates before every VLM call; user can disable for non-Omron devices |

---

### 10.9 Privacy Boundary Table

| Path | Data leaves phone? | Stored where? | User control |
|------|-------------------|---------------|------------|
| AMM (`127.0.0.1:8765`) | ❌ No | AMM app-private storage | Toggle off → AMM skipped |
| Ollama (`localhost:11434`) | ❌ No | Ollama model cache | Toggle off → Ollama skipped |
| OpenAI-compatible API | ✅ Yes | API provider's servers | Toggle off + no key → API skipped |
| Template matcher | ❌ No | N/A (in-browser) | Always available |
| Manual entry | ❌ No | N/A | Always available |

Only the OpenAI-compatible path transmits image data off-device, and only if the user explicitly configures it.

---

### 10.10 End-to-End Sequences + Phase 1 Exit Metric

**Happy path:**

```
User takes photo → bp-app rotates 90° → sends to AMM
  → AMM returns "{sys:118,dia:78,bpm:59}" in 3.2s
  → bp-app parses JSON, all range checks pass
  → Green confidence badge, user taps Save
```

**Failure path 1 — AMM timeout:**

```
User takes photo → bp-app rotates 90° → sends to AMM
  → 15s timeout
  → Toast: "Falling back to Ollama…"
  → Ollama succeeds → same save flow
```

**Failure path 2 — All VLMs fail:**

```
User takes photo → AMM timeout → Ollama unavailable → API offline
  → bp-app shows "Couldn't read automatically — tap the display to help us"
  → User draws LCD ROI → template matcher extracts 118/78/59
  → Amber confidence badge (one check borderline) → user edits → Save
```

**Phase 1 exit metric:**

bp-app on a phone with AMM installed extracts SYS/DIA/PULSE from the full `Bloodpressure Samples/` set (7+ photos, expanding to 30+) with a full-match rate that **meets or exceeds the measured Qwen 3.5 4B + rotate90 benchmark** from Phase 0 Test 2.

- If benchmark = 5/7 → exit criterion = 5/7 (or better)
- If benchmark = 7/7 → exit criterion = 7/7
- Median response time ≤ 5s per image on a mid-range 2024 Android

The target is **grounded in measured reality**, not aspirational 90 %.

---

## 11. Open Questions

- *(Resolved as Phase 0 test)* **Chrome mixed-content / PNA trap:** see §8 Phase 0 — Test 1. No longer an open question; it is a go/no-go gate that must pass on real devices before any AMM native code is written.
- **Model licensing at the APK level:** SmolVLM is Apache 2.0. Qwen2.5-VL is Apache 2.0. Gemma is under the Gemma license (permissive but custom). Confirm all default-path models are redistributable, or download-only-from-HF (user pulls, we don't rehost).
- **Shared memory path for bitmaps:** JNI byte-array copy for a 4080×3060 image is 40 MB of allocation. Investigate `AHardwareBuffer` or `ASharedMemory` for zero-copy handoff once Phase 1 is working.
- **Versioning the API:** `/v1/…` is locked now, but schema additions to `response_schema` / `capabilities` need a compatibility contract. Propose: additive only within v1; breaking changes move to `/v2/…` alongside.
- **Does the Android "Matrix Model" name collide with trademark?** Confirm before public launch.

---

## 12. Relationship to Existing Docs

| Document | Role after this plan |
|---|---|
| `vision-models-fdr.md` | **Implementation reference** for Phase 1. Its layer-by-layer breakdown is the concrete engineering checklist; this doc is the product frame around it. |
| `vision-models-fdr-REVIEW.md` | Still the critique that warns against overbuilding. Every Phase here must justify itself on evidence, not aspiration. |
| `260419-v01 vision-OCR-new-direction.md` | **Gate for Phase 0 → Phase 1 transition.** If its SUMMARY.md shows a cheap engine wins the BP case, Phase 1 proceeds on its own merits (ASR, doc indexing) rather than being forced by BP. |
| `repoanalysis.md` | Needs a follow-up edit to reflect that `android-matrix-model` is now framed as a platform, not a chat app variant. |
| `README.md` | Needs re-aimed intro — "Self-hosted AI hub for your phone" rather than "SmolChat fork". |
| `bp-app/BP-FRD.md` | Matrix Model integration section added (§4.3 "External Vision-Language Model Engines", §19). Covers engine priority, editable prompts, fallback logic, rotate90 preprocessing, 7-seg template matcher, client-side confidence, and schema-guided response handling. |

---

## 13. Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-04-19 | Platform framing established | Existing docs were feature-scoped (BP OCR); user's intent is a general on-device AI hub serving many apps |
| 2026-04-19 | Sideload-first distribution | Play Store's LLM-weight and local-server policies are hostile to the self-hosted promise |
| 2026-04-19 | Models downloaded, not bundled | Keeps APK small; lets users pick model tier; respects bandwidth/storage |
| 2026-04-19 | Loopback HTTP as primary IPC | Lowest friction for PWA clients; AIDL reserved for native callers who want lower latency |
| 2026-04-19 | bp-app stays multi-engine | Matrix Model is the best-quality option but never a hard dependency; bp-app must work on any phone whether Matrix is installed or not |
| 2026-04-20 | AMM stays local-only; no proxy mode | Proxy-to-cloud muddles AMM's "on-device" identity and adds security surface (Keystore, rate limits, CORS re-forwarding) for little benefit. bp-app calls Ollama/OpenAI directly in its own engine priority list |
| 2026-04-20 | rotate90 is required preprocessing | Experimental evidence: Gemma 4 went from 0/16 full-match (original) to 4/4 (rotate90). Larger models need it; smaller models may prefer original. Try rotate90 first, fall back to original |
| 2026-04-20 | Minimal prompts beat verbose prompts | "Only the 3 numbers, comma-separated" outperforms 7-seg descriptions and range instructions. A/B test before locking the default |
| 2026-04-20 | Client-side confidence, not model-reported | Range checks + pulse-pressure sanity are more reliable than a 1–3 GB VLM self-assessing "high|medium|low" |
| 2026-04-20 | 7-seg template matcher is a real fallback tier | ~80% accuracy, zero network, <1s — deserves a UI slot between "VLM failed" and "type it yourself" |
| 2026-04-20 | SmolVLM 256M is unvalidated on 7-seg | Every tested sub-1 GB model hit 24–31 %. Qwen 3.5 4B (~3.4 GB) is the smallest proven model. Do not declare SmolVLM the default without measuring it first |
| 2026-04-20 | Caller-provided editable prompts | bp-app (and any client) sends its own prompt; AMM does not hard-code domain logic; default BP prompt ships in bp-app and is user-editable |
| 2026-04-20 | Future roadmap formalised | ASR, general OCR, file conversion, object recognition / camera streaming, and document indexing are documented as post-v3 capabilities |
| 2026-04-20 | bp-app integration contract formalised in §10 | Single source of truth for handshake, request/response, fallback ladder, and exit metrics; prevents drift between AMM and bp-app teams during implementation |
| 2026-04-20 | PNA / mixed-content is a Phase 0 go/no-go, not an open question | One afternoon with a real phone + stub server answers whether the HTTPS→loopback shape is viable; if red, architecture must change before JNI is written |
| 2026-04-20 | Preprocessing (rotate90) owned by bp-app, not AMM | AMM receives already-rotated images; this keeps AMM domain-agnostic and lets bp-app experiment with orientation per-model without re-releasing AMM |
| 2026-04-20 | Exit criteria grounded in measured benchmark, not aspiration | Phase 1 target = whatever Qwen 3.5 4B + rotate90 scores on the sample set; if 5/7, the bar is 5/7 |
| 2026-04-20 | Request/response contract: caller-provided prompt, no hard-coded domain logic in AMM | AMM forwards the prompt unchanged; bp-app owns the default, the A/B test, and the editable UI |
