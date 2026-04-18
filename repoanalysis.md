# android-matrix-model — Repo Analysis

**Repo:** `justinaquino/android-matrix-model`
**Upstream origin:** `shubham0204/SmolChat-Android` (Apache 2.0)
**Analysed:** 2026-04-18
**Working dir:** `/home/justin/opencode260220/android-matrix-model`

---

## 1. Identity

| Field | Value |
|---|---|
| Project name | SmolChat (fork as `android-matrix-model`) |
| Purpose | On-device LLM chat for Android via llama.cpp |
| Primary languages | Kotlin (app + libraries), C/C++ (inference), a touch of Java (JNI) |
| Target platform | Android `minSdk 26`, `targetSdk 35` |
| Build system | Gradle 8.13.0 (Kotlin DSL), CMake 3.22.1, NDK 27.2.12479018 |
| License | Apache License 2.0 (`LICENSE`) |
| Current version | v15 (`versionCode=15`, `versionName="15"`) |
| Upstream author | Shubham Panchal |
| Git state | 176 commits; HEAD `9379684` "release: prepare for release v15" (2026-04-17) |

---

## 2. What It Does

SmolChat is an Android app that runs quantised GGUF language models **fully on-device** using `llama.cpp` compiled through the NDK. It wraps the llama.cpp C API in a Kotlin `SmolLM` class and ships a Jetpack Compose chat UI. Users download models from HuggingFace Hub (or side-load `.gguf` files), start chats, tune inference parameters (temperature, min-p), edit system prompts, organise chats into folders, and optionally run one-shot "tasks" (summarise, rewrite) that don't persist context.

Everything runs locally — no network calls at inference time. HuggingFace Hub is used only for model discovery and download. An optional speech-to-text path uses Moonshine.

---

## 3. Module Architecture

Multi-module Gradle build (`settings.gradle.kts:32-35`):

| Module | Role |
|---|---|
| `app/` | Main application. Namespace `io.shubham0204.smollmandroid`. Compose UI, Room persistence, DI, business logic. |
| `smollm/` | Android library. JNI bridge to llama.cpp. Namespace `io.shubham0204.smollm`. Produces reusable `smollm.aar`. |
| `smolvectordb/` | Android library. Lightweight vector DB scaffolding for planned on-device RAG. Not yet wired into the app. `minSdk 24`. |
| `hf-model-hub-api/` | Pure Kotlin/JVM library. Ktor-based HuggingFace Hub client (`HFModels`, `HFModelInfo`, `HFModelTree`, `HFModelSearch`). No Android deps. |
| `llama.cpp/` | Git submodule of `ggerganov/llama.cpp` pinned to upstream master (not a tag). |
| `metadata/` | F-Droid / Play Store localised descriptions + screenshots. |
| `resources/` | Icons, marketing screenshots. |
| `docs/` | Engineering docs (integration, llama.cpp sync, ARM build flags, release checklist). |

### App package layout (`app/src/main/java/io/shubham0204/smollmandroid/`)

```
data/     AppDB.kt, ChatsDB.kt, MessagesDB.kt, ModelsDB.kt, TasksDB.kt,
          FoldersDB.kt, HFModelsAPI.kt, SharedPrefStore.kt
llm/      ModelsRepository.kt, SmolLMManager.kt, speech2text/
ui/       screens/{chat,model_download,manage_tasks,manage_asr},
          components/, theme/, preview/
KoinAppModule.kt        ← DI graph
SmolChatApplication.kt  ← Application entry
MainActivity.kt         ← launcher (routes to ChatActivity or DownloadModelActivity)
```

---

## 4. Tech Stack

### Kotlin / Android
- Kotlin 2.0.0, AGP 8.13.0, JVM target 17
- Jetpack Compose (BOM 2024.10.01), Material 3, Navigation 2.8.3
- Room 2.6.1 + KSP compiler
- Paging 3.3.5
- **Koin 3.5.6 annotation DI** (compile-time graph via KSP — no reflection)
- Coroutines 1.10.1, Immutable Collections 0.4.0
- Markwon 4.6.2 + Prism4j 2.0.0 (markdown + code highlighting in chat)

### Native / ML
- `llama.cpp` submodule
- CMake 3.22.1, NDK 27.2.12479018
- GGML / GGUF format
- ABI variants built for ARMv7 NEON, ARMv8.2 (ASIMD + dotprod + FP16), ARMv8.4 (+ SVE + i8mm). See `smollm/src/main/cpp/CMakeLists.txt:118-133`.
- Vulkan backend noted as future work (not integrated)

### Networking & serialisation
- Ktor Client 3.0.2 (OkHttp engine), Kotlinx Serialization 1.7.3, OkHttp 4.12.0
- Ketch 2.0.5 for GGUF download management

### Other
- Moonshine Voice 0.0.48 (ASR, optional)
- Feather Icons 1.1.1
- AndroidX core-ktx 1.15.0, lifecycle 2.8.7, activity-compose 1.9.3

Pinned versions live in `gradle/libs.versions.toml`.

---

## 5. Key Features

From `README.md` + package inspection:

1. Multi-chat management with persistence (Room).
2. GGUF model add / remove; per-model system prompt + inference params.
3. Task mode — ephemeral inference without chat history (summarise, rewrite).
4. HuggingFace Hub search + download, with offline detection fallback.
5. Speech-to-text via Moonshine (`ManageASRActivity`, `AudioTranscriptionService`).
6. Markdown + syntax-highlighted code in chat bubbles.
7. Folder organisation for chats.
8. Per-phase benchmarking (prompt eval / generation tok/s).
9. Jinja2 chat-template support passed through to llama.cpp (`LLMInference.h:26`).
10. Android intent-share receiver (share text into a new chat).
11. Planned: in-app RAG (smolvectordb placeholder module exists).

---

## 6. Entry Points

| Activity | Export | Role |
|---|---|---|
| `MainActivity.kt` | exported | Launcher — routes to chat or model-download |
| `ChatActivity.kt` | exported | Chat UI; receives shared-text intents |
| `DownloadModelActivity.kt` | internal | Model download / import |
| `ManageTasksActivity.kt` | internal | Task CRUD |
| `ManageASRActivity.kt` | internal | Speech-recognition settings |

### JNI bridge
```
SmolLM.kt  →  smollm.cpp (JNI)  →  LLMInference.cpp  →  llama.cpp C API
```
Token streaming surfaces to UI as `Flow<String>` for incremental rendering.

---

## 7. Build & Run

```bash
git clone --depth=1 https://github.com/justinaquino/android-matrix-model
cd android-matrix-model
git submodule update --init --recursive
# Android Studio auto-builds on open;
# manual: Build → Rebuild Project
```

| Pin | Value |
|---|---|
| `compileSdk` | 35 |
| `targetSdk` | 35 |
| `minSdk` | 26 (app), 24 (smolvectordb) |
| NDK | 27.2.12479018 |
| CMake | 3.22.1 |
| JVM target | Java 17 |

### Useful Gradle targets
- `./gradlew :smollm:assemble` → `smollm.aar` for reuse in other projects
- `./gradlew :app:assembleDebug` / `assembleRelease`

### Release signing
Expects env vars `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEYSTORE_ALIAS`, `RELEASE_KEY_PASSWORD`; keystore at `../keystore.jks`. Minification on, ProGuard rules at `app/proguard-rules.pro`, `dependenciesInfo.includeInApk = false` for F-Droid reproducibility.

---

## 8. Submodules

`.gitmodules`:
```
[submodule "llama.cpp"]
  path = llama.cpp
  url  = https://github.com/ggerganov/llama.cpp
```

Pinned to upstream master, synced on each release. Sync procedure in `docs/update_llamacpp_submodule.md`.

---

## 9. Notable Design Decisions

| # | Decision | Where | Why |
|---|---|---|---|
| 1 | Multi-ABI `.so` variants (v7, v8.2, v8.4 × feature matrix) | `CMakeLists.txt:118-133` | Maximise perf per device without runtime CPU detection cost |
| 2 | Streaming token output via Kotlin `Flow<String>` | `SmolLM.getResponse()` | Responsive chat UI; no blocking |
| 3 | Koin KSP annotation DI | `KoinAppModule.kt` | Compile-time DI graph; no reflection |
| 4 | Room + Flow-reactive DAOs | `data/*DB.kt` | Single source of truth across UI |
| 5 | `storeChats` flag toggles chat vs task mode | `SmolLMManager.kt` | Reuses same inference path; task mode is ephemeral |
| 6 | Jinja2 chat templates forwarded to llama.cpp | `LLMInference.h:26` | Works with arbitrary model prompt formats |
| 7 | smolvectordb stub module | `smolvectordb/` | Signals planned RAG without bloating app |
| 8 | HF Hub connection pre-check before list fetch | commit `f896997` | Graceful offline UX |
| 9 | Edge-to-edge Compose layout | `MainActivity:33` | Modern Android look |
| 10 | ProGuard rules preserve Koin `@Single` + reflective libs | `app/proguard-rules.pro` | Minification-safe |

---

## 10. Documentation

`/docs/`:
- `integrating_smollm.md` — consume `smollm.aar` from another app; `SmolLM` API walk-through.
- `update_llamacpp_submodule.md` — submodule sync steps.
- `build_arm_flags.md` — CPU extension flags per ABI variant.
- `release-checklist.md` — sync llama.cpp, update `CHANGELOG.md`, bump version, tag.

`/PRIVACY_POLICY.md` — no data collection, inference is strictly local, effective 2025-05-09.

`/metadata/en-US/` — F-Droid/Play short + long descriptions, screenshots, changelog-per-version.

---

## 11. Tests

Scaffolding is present but coverage is thin:

- `/app/src/test/` — JVM unit test tree (declared in `app/build.gradle.kts:142-144`).
- `/app/src/androidTest/` — Espresso + Compose UI test deps wired.
- `/hf-model-hub-api/src/test/java/HFModelTests.kt` — the most substantive test file, covers HF API client.
- No visible test coverage for the native/JNI boundary or the Room DAOs beyond smoke-level scaffolding.

---

## 12. Version History Snapshot

| Version | Highlights |
|---|---|
| **v15** (2026-04-17, HEAD) | llama.cpp sync → Gemma 4 support; HF offline detection fix; copy/share polish |
| v14 (`40eae70`) | Moonshine ASR integration |
| v13 (`c081c41`) | JNI crash fixes |
| v12 (`769519d`) | Jinja chat-template processing |
| v11 ↓ | Chat UX foundation, model management, downloads |

A mature, actively maintained project with regular upstream sync.

---

## 13. Apparent Roadmap

From `README.md` Future section:

- Auto-name new chats from first message (ChatGPT-style).
- In-chat message search.
- Bluetooth / HTTP / Wi-Fi bridge between desktop client and on-device inference.
- Auto-scroll during generation.
- RAM consumption metric.
- **RAG** via smolvectordb + Android-Doc-QA integration.
- **Vulkan GPU backend** via llama.cpp Vulkan path (compile test pending).

No formal `ROADMAP.md` or `TODO.md` — intent is carried in README and module stubs.

---

## 14. Key File Reference

| Path | Purpose |
|---|---|
| `settings.gradle.kts` | Module list |
| `gradle/libs.versions.toml` | Centralised version catalog |
| `app/build.gradle.kts` | App module build + signing |
| `app/src/main/AndroidManifest.xml` | Activities, permissions, intent filters |
| `app/src/main/java/io/shubham0204/smollmandroid/MainActivity.kt` | Launcher |
| `app/src/main/java/io/shubham0204/smollmandroid/KoinAppModule.kt` | DI module |
| `app/src/main/java/io/shubham0204/smollmandroid/data/AppDB.kt` | Room DB entry |
| `smollm/src/main/java/io/shubham0204/smollm/SmolLM.kt` | Kotlin inference API |
| `smollm/src/main/cpp/LLMInference.h` | Native inference wrapper |
| `smollm/src/main/cpp/smollm.cpp` | JNI bindings |
| `smollm/src/main/cpp/CMakeLists.txt` | ABI/feature build matrix |
| `smolvectordb/src/main/cpp/VectorDB.cpp` | Vector DB native impl (stub) |
| `hf-model-hub-api/` | HF Hub Kotlin/JVM client |
| `.gitmodules` | llama.cpp submodule pin |
| `CHANGELOG.md` | Per-release notes |
| `PRIVACY_POLICY.md` | Local-only processing statement |

---

## 15. Thesis — What This Repo Teaches

1. **Native inference on Android is tractable** when the runtime (llama.cpp) is already packaged for cross-compile. The win is in packaging, not writing inference from scratch.
2. **ABI fan-out is worth the build-time cost** — one `.so` per relevant CPU feature set gives measurable throughput gains on modern ARM chips without runtime branching.
3. **JNI boundaries stay thin.** `LLMInference.{cpp,h}` is the only C++ layer the app touches; everything else is Kotlin. That keeps JNI crash surface small.
4. **Flow-based streaming is the right shape** for token output — maps cleanly to Compose state without manual synchronisation.
5. **Koin KSP + Room KSP + Compose** is a viable modern Android triad with near-zero reflection, good cold-start characteristics.
6. **Fork stance:** the `justinaquino/android-matrix-model` fork currently tracks upstream 1:1 — any divergence should be documented here before it accumulates.

---

## 16. Open Questions for This Fork

- Is the intent to diverge from upstream SmolChat, or to contribute back?
- Will the `smolvectordb` module be the integration point for a Mneme-adjacent RAG experiment?
- Does the rename to `android-matrix-model` imply a rebrand, or is it a private working title?
- Will Vulkan and the desktop-bridge features land here first, or be pulled from upstream?

Resolve these before meaningful code changes, so the fork's identity and merge strategy are deliberate rather than incidental.

---

## 17. Vision Model Support Assessment

**Can this repo run vision models?** The `llama.cpp` submodule (commit `c08d28d`, 2026-04-05) **already contains** `libmtmd` — the official multimodal library supporting Gemma 4V, Qwen2.5-VL, SmolVLM, LLaVA, and 15+ other vision architectures. However, the app currently cannot use it.

### What's Already There
- `tools/mtmd/` in the llama.cpp submodule with full C API (`mtmd.h`)
- Model adapters for `gemma4v.cpp`, `qwen2vl.cpp`, `qwen3vl.cpp`, `llava.cpp`, `siglip.cpp`, etc.
- `mtmd-cli.cpp` reference implementation showing text + image tokenization
- The submodule commit is from April 2026 — libmtmd has been stable for ~1 year

### What's Missing
- `llama.cpp/` working directory is **empty** (submodule not initialized)
- `CMakeLists.txt` does **not** build `mtmd` targets
- JNI bridge (`smollm.cpp`) has **no image** methods
- Kotlin API (`SmolLM.kt`) has **no image** types
- Compose UI has **no image picker** or preview
- Model download logic handles only **one** GGUF file, not the required `text.gguf` + `mmproj.gguf` pair

### Connection to bp-app
The `bp-app` PWA (blood pressure OCR) achieved a **FULL_MATCH** with Gemma 4:e2b + rotate90 preprocessing, but cannot deploy a 7.2 GB model in a browser. Running a vision model **on-device via this Android app** is a viable path:

1. **Phase 1:** Build a minimal `bp-vision-service` APK using the mtmd stack (2-4 weeks)
2. **Phase 2:** Expose local HTTP API that bp-app can call via `fetch('http://localhost:8765/ocr')`
3. **Phase 3:** Merge the vision JNI changes into SmolChat proper

See `vision-models-fdr.md` for full challenge analysis, three implementation strategies, model recommendations (Qwen2.5-VL 3B, SmolVLM 256M), and a phased implementation checklist.

---

## 18. Related Documents

| Document | Purpose |
|----------|---------|
| `vision-models-fdr.md` | Full functional design for adding vision model support to SmolChat-Android |
| `bp-app/OCR_EVALUATION_REPORT.md` | Gemma 4:e2b benchmark showing rotate90 achieves FULL_MATCH on BP monitor OCR |
