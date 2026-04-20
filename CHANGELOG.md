## AMM v1.0.0 (2026-04-20)

**Rebrand:** SmolChat → AMM (Android Matrix Models)

### New Features
- **Vision Model Support** — On-device VLM inference via llama.cpp `libmtmd`
  - Load text GGUF + mmproj projector pairs
  - Accept JPEG/PNG images for vision prompting
  - Streaming and blocking Kotlin APIs
- **Local HTTP API** — Embedded NanoHTTPD server on `127.0.0.1:8765`
  - `GET /health` — service health check
  - `GET /status` — model load status
  - `POST /vision` — multipart upload (`image` + `prompt`) → JSON response
  - Full CORS headers for PWA cross-origin access
- **Vision Hub UI** — New "Vision Hub" screen accessible from chat drawer
  - Toggle HTTP service on/off
  - Load/unload vision models
  - File discovery heuristic for text model + mmproj pairs
- **Rebrand** — App renamed to "AMM", release APKs named `AMM_v*.apk`

### Technical Changes
- Added `VisionInference.h/cpp` C++ wrapper around `libmtmd`
- Extended JNI bridge with 9 vision-specific native methods
- Extended `SmolLM` Kotlin class with vision model APIs
- Added `VisionLMManager` (Koin singleton) for vision inference lifecycle
- Added `HttpService` foreground service with notification
- Updated CMake to build `libmtmd`, link `stb_image`, enforce C++17
- Added `NanoHTTPD` dependency for embedded HTTP server

### Known Limitations
- Vision model download requires manual import (no paired download UI yet)
- No GPU acceleration yet (CPU-only inference)
- PNA/mixed-content go/no-go test pending on real devices
- Benchmark results pending (requires Qwen 3.5 4B + rotate90 on BP sample set)

---

## SmolChat v15 (Previous)

- Sync with upstream llama.cpp brings in support for Gemma 4 models (#128 and #130)
- 'copy' and 'share' buttons below a message, now, do not copy the thinking contents of the model (
  #126 by @Nittur)
- Fix a bug where the app crashes when trying to show a list of available models from HuggingFace in
  the absence of an internet connection
