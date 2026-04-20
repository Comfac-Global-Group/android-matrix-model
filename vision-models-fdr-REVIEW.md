# Review of `vision-models-fdr.md`

**Reviewer:** Claude (acting as technical critic)
**Date:** 2026-04-19
**Audience:** Kimi (implementer) + Justin (decision-maker)
**Intent:** Select the course of action with **highest success probability × lowest resource cost**, and express it as instructions Kimi can execute without further planning.

---

## TL;DR

The FDR is thorough on "what libmtmd is" but makes three strategic errors:

1. **It solves the wrong problem.** The real goal is BP-monitor OCR; the FDR optimises for "make SmolChat multimodal." Those are not the same thing. A 2.2 GB vision LLM is massive overkill for reading three 7-segment digit groups.
2. **It picks the highest-complexity path** (Strategy B/C hybrid with an HTTP foreground service and JNI + mtmd + model bundling) without a prior validation step proving the approach is needed.
3. **It skips the cheap experiment.** No one has yet verified that (a) mtmd compiles under the Android NDK at all, or (b) any VLM actually reads 7-segment LCDs reliably — and VLMs are notoriously bad at 7-seg because the training data is almost all natural images.

**Recommendation:** Replace the FDR's Phase-1 plan with a **one-day validation spike**, then let the spike's result pick the architecture. Do not start JNI/mtmd work before the spike. Do not bundle models before measuring accuracy.

---

## 1. What the FDR Gets Right

- Correctly identifies `libmtmd` exists in the llama.cpp submodule at commit `c08d28d`.
- Correctly identifies current CMake does not link `mtmd` or `clip`.
- Correctly notes the two-file requirement (`.gguf` + `.mmproj.gguf`).
- Correctly flags the uninitialised submodule.
- The layer-by-layer challenge table (§4) is honest about blocker vs medium vs low.
- Risk table (§9) acknowledges libmtmd API churn and OOM on 4 GB devices.

Keep these sections when revising.

---

## 2. Critical Issues with the FDR

### 2.1 Goal confusion

The document is titled "Vision Model Support" but §6 Strategy C and the BP prompt in §7 reveal the actual goal: **read a BP monitor**. These are different orders of magnitude:

| Goal | What's needed |
|---|---|
| "Chat with an image" (general VLM) | 2–4 GB multimodal model, mtmd, full pipeline |
| "Read three numbers off a 7-segment LCD" | ~10 MB text-recognition model OR a 100-line image-processing heuristic |

By conflating them, the FDR commits to the expensive path before proving the cheap path fails.

### 2.2 7-segment LCD is a known VLM weakness

Qwen2.5-VL, Gemma-3-Vision, and LLaVA are trained overwhelmingly on natural images + document OCR. 7-segment LCDs (especially BP monitors, which often have thin strokes, low contrast, and glare) are a well-documented failure mode. The FDR's "⭐⭐⭐ Excellent" OCR rating for Qwen2.5-VL 3B is **not the same as** "reliable on 7-seg." That needs measurement, not assumption.

### 2.3 Bundling GGUFs as APK assets

§6 Phase 1 says:

> Bundles SmolVLM 256M or Qwen2.5-VL 3B (text + mmproj GGUFs)

- Qwen2.5-VL 3B = **2.2 GB**. Play Store bundle cap is 2 GB (AAB with install-time modules); OBBs are deprecated. Not viable.
- SmolVLM 256M = **~400 MB**. Viable but forces every user to download 400 MB of assets even if they never use vision. Prefer a runtime download.

### 2.4 Effort estimates are optimistic

"1–2 weeks" for Phase 1 undercounts:
- mtmd has never been proven to compile on Android NDK in this repo.
- Multi-ABI fan-out (v7 NEON, v8.2 dotprod, v8.4 SVE/i8mm) × mtmd's clip.cpp dependencies.
- Foreground Service + notification + battery-exemption dialog + Doze-resilience testing.
- HTTP server binding to localhost only + CORS handling for the bp-app PWA.
- Image resize/RGB conversion in JNI.
- JSON response parsing for sys/dia/pulse (LLM output is famously non-deterministic).

A realistic estimate is 3–6 weeks for someone new to this codebase. This is not "efficient use of resources" if the same outcome can be reached with ML Kit in one day.

### 2.5 Missing alternative: Google ML Kit Text Recognition

Not mentioned in the FDR. Should have been evaluated:

| Option | Size | Offline | Cost | Effort |
|---|---|---|---|---|
| **ML Kit Text Recognition v2** | ~10 MB | Yes | Free | 1 day integration |
| Tesseract Android (tess-two) | ~50 MB + traineddata | Yes | Free | 2–3 days |
| Custom 7-seg TFLite CNN | ~1 MB | Yes | Free | 1 week train + deploy |
| Qwen2.5-VL 3B via mtmd | ~2.2 GB | Yes | Free but heavy | 3–6 weeks |
| Gemini / GPT-4V via API | 0 MB local | **No** | $/image | 1 day |

For reading `SYS / DIA / PULSE`, ML Kit is the obvious first thing to try.

### 2.6 Upstream SmolChat is a free asset being ignored

The repo is a fork of `shubham0204/SmolChat-Android`. Upstream's roadmap (README "Future") hints at multimodal features but hasn't shipped. Key insight: **let upstream do the hard JNI/mtmd work.** When they ship, you sync the submodule and inherit the integration. Waiting is an option the FDR doesn't list.

### 2.7 No validation spike before architecture commit

The FDR goes: analysis → strategy selection → 11-week implementation. It skips: **take three photos of your BP monitor, run them through three candidate OCR approaches, measure accuracy.** That spike is a day of work and changes everything downstream.

---

## 3. Revised Course of Action

Ordered by increasing cost. Stop at the first tier that solves the real problem.

### Tier 0 — Validation Spike (1 day, Kimi can do this)

**Goal:** Determine which OCR approach reaches ≥ 90 % field accuracy on real BP-monitor photos.

Concrete tasks:

1. Justin provides 10 photos of BP monitor readings (varied lighting, angles). Known-good labels for sys/dia/pulse on each.
2. Kimi implements three parallel test harnesses in `/home/justin/opencode260220/bp-app/experiments/`:
   - `ml_kit_test.html` — load photo, run Google ML Kit Text Recognition (browser JS version via `@mediapipe/tasks-text` or native Android wrapper), print raw detected strings.
   - `tesseract_test.html` — same photos through Tesseract.js with digit-only whitelist (`-c tessedit_char_whitelist=0123456789`).
   - `gemini_vision_test.py` — same photos through Gemini Flash vision API (cheap reference baseline — not shipped, just for accuracy ceiling).
3. Kimi produces `experiments/results.csv`: photo_id, expected_sys, expected_dia, expected_pulse, ml_kit_output, tesseract_output, gemini_output, + per-field correctness boolean.
4. Kimi produces a one-page markdown summary with accuracy percentages per engine.

**Exit gates:**
- If ML Kit ≥ 90 % → **go to Tier 1. Drop the VLM plan entirely.**
- If Tesseract ≥ 90 % but ML Kit < 90 % → **go to Tier 1b (Tesseract path).**
- If both < 90 % and Gemini ≥ 90 % → proceed to Tier 2.
- If all three < 90 % on 7-seg → the problem is harder than OCR; consider Tier 3 (custom model).

### Tier 1 — ML Kit in bp-app (1–2 days if Tier 0 passes)

Since bp-app is a PWA, there are two sub-options:

- **1a: Pure PWA + MediaPipe Text Detection** — runs in browser, offline, ~5 MB WASM + model. Integrate via `@mediapipe/tasks-text`.
- **1b: Trivial Android wrapper** — a 100-line Android app with WebView + native ML Kit bridge via JS interface, if 1a's accuracy is too low. Still ~1 week, still dwarfs the FDR plan.

**This is the efficient-resources path.** No JNI, no mtmd, no foreground service, no GGUF bundling, no upstream coupling.

### Tier 1b — Tesseract Fallback (if ML Kit loses to Tesseract)

Same architecture as Tier 1a, different engine: Tesseract.js with `letsgodigital` traineddata (a community model specifically trained on 7-segment digits, ~5 MB). Good for LCDs.

### Tier 2 — Cloud VLM (if local OCR fails)

If Tier 0 shows local OCR cannot handle the monitor but Gemini/GPT-4V can, weigh:

- Privacy cost: images leave the device.
- Network cost: requires connectivity at capture time.
- $ cost: ~$0.001/image for Gemini Flash — trivial.

Only pursue on-device VLM (Tier 3) if **all three** of those are disqualifying.

### Tier 3 — On-device VLM (last resort, 3–6 weeks, Kimi+Claude)

Only if Tiers 0–2 all fail.

Narrow scope drastically vs the FDR:

- Do NOT build a standalone `bp-vision-service` APK. Too much surface area.
- Do NOT add HTTP server + bp-app HTTP client. Added complexity.
- Do NOT target SmolChat chat UI integration. Out of scope.
- DO wait for `shubham0204/SmolChat-Android` upstream to add vision support (likely; mtmd is in the submodule).
- DO, if upstream is too slow, fork just enough to wire mtmd into `smollm/` as one new Kotlin method `SmolLM.generateFromImage(bitmap, prompt)`. No UI work. bp-app consumes it via a thin custom URL intent rather than HTTP.

This is still more expensive than Tier 1 by an order of magnitude. It should be avoided, not pursued by default.

---

## 4. Instructions for Kimi

**Task for Kimi: execute Tier 0.** Stop there. Report results. Wait for Justin to pick the next tier.

### 4.1 Setup

```bash
cd /home/justin/opencode260220/bp-app
mkdir -p experiments/input experiments/output
# Justin will drop ~10 JPG/PNG photos into experiments/input/ with sidecar .json
# files of the form {"sys": 118, "dia": 78, "pulse": 59}
```

### 4.2 Three test harnesses

Create each under `experiments/`:

**File: `experiments/ml_kit_test.html`**
- Loads every image in `input/`.
- Runs MediaPipe Text Detection (`@mediapipe/tasks-text` from CDN).
- Outputs detected text strings to `output/ml_kit.json` as `{photo_id: {raw_strings: [...]}}`.
- Keep the HTML self-contained — no build step.

**File: `experiments/tesseract_test.html`**
- Same loop, uses Tesseract.js from CDN.
- Config: `lang: 'letsgodigital'` (or `eng` if traineddata not available), `tessedit_char_whitelist: '0123456789'`.
- Writes `output/tesseract.json`.

**File: `experiments/gemini_vision_test.py`**
- Python script using `google-genai` SDK (Justin will supply API key via env var `GEMINI_API_KEY`).
- Model: `gemini-2.5-flash` (or latest flash).
- Prompt:
  ```
  This is a photo of a blood pressure monitor's LCD display.
  Read only the three numeric values: systolic, diastolic, pulse.
  Reply in this exact format and nothing else:
  SYS: <int>, DIA: <int>, PULSE: <int>
  ```
- Writes `output/gemini.json`.

**File: `experiments/parse_and_score.py`**
- Reads `input/*.json` as ground truth, reads all three output files.
- For each photo, extract numeric values via regex from each engine's output.
- Compute per-field correctness (sys/dia/pulse each boolean).
- Writes `output/results.csv`.
- Writes `output/SUMMARY.md` with accuracy percentages per engine, and flags any photo where all three engines disagree with truth.

### 4.3 Exit criteria for Kimi

- All three test harnesses run end-to-end without hand-holding from Justin.
- `SUMMARY.md` exists and is concise (≤ 40 lines).
- `results.csv` is sortable in a spreadsheet.
- **Do NOT** start implementing Tier 1, Tier 2, or Tier 3 until Justin reviews `SUMMARY.md` and decides.

### 4.4 What Kimi must NOT do (hard constraints)

- Do **not** touch `android-matrix-model/smollm/src/main/cpp/CMakeLists.txt`.
- Do **not** initialise the llama.cpp submodule.
- Do **not** add `mtmd` to the build.
- Do **not** write any JNI code.
- Do **not** download GGUF files.
- Do **not** build a foreground service.
- Do **not** modify the SmolChat fork at all.

All of that is downstream of the Tier 0 result and may turn out to be unnecessary. Resist.

### 4.5 Environment notes

- `bp-app` already has a local dev server (see its `package.json`). The HTML harnesses can be served from there.
- Browser cache will hold MediaPipe + Tesseract WASM/model after first run, so subsequent runs are fast.
- Gemini test runs once per photo; rate limits are not an issue at 10 images.

---

## 5. Why This Beats the FDR on Both Axes

| Axis | FDR plan | Revised plan |
|---|---|---|
| **Success probability** | Medium — depends on 7-seg VLM accuracy (unvalidated) | High — decision is evidence-based after Tier 0 |
| **Time to working OCR** | 3–6 weeks | 1 day (Tier 0) + 1–2 days (Tier 1) if ML Kit works |
| **Code complexity** | JNI + mtmd + CMake + HTTP server + UI | Plain HTML + JS in bp-app |
| **Resource cost** | 2.2 GB model, foreground service, battery budget | ~5 MB WASM, runs in existing PWA |
| **Kimi-implementable** | No — JNI/NDK is too architecture-heavy for an unsupervised run | Yes — three self-contained test harnesses |
| **Reversibility if wrong** | Low — lots of code committed before result known | High — one day of experiments, discardable |

---

## 6. When to Revisit the FDR

Bring the original FDR back off the shelf if **all** of these are true after Tier 0:

- No cloud VLM is acceptable (privacy, offline requirement).
- No on-device text-recognition library hits 90 % on the target monitors.
- Justin is willing to fund 3–6 weeks of dev.
- Upstream SmolChat has not shipped vision support in the meantime.

Otherwise, file the FDR under "good research, not the right tool."

---

## 7. One-Line Summary

> **"Prove OCR works before wiring up a 2 GB model."** — Do Tier 0 today, decide tomorrow.
