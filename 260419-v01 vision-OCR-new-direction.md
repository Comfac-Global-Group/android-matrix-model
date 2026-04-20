# 260419-v01 — Vision / OCR New Direction

**Date:** 2026-04-19
**Status:** Active — Tier 0 spike pushed, awaiting results
**Owner:** Justin
**Implementer:** Kimi (Tier 0 harnesses) → TBD per result (Tier 1+)
**Supersedes:** `vision-models-fdr.md` (kept as reference, deprioritised)
**See also:** `vision-models-fdr-REVIEW.md` (critique that drove this pivot)

---

## 1. What Changed

The previous Functional Design Requirements document (`vision-models-fdr.md`) committed to a 3–6 week on-device VLM integration (mtmd + JNI + HTTP foreground service) before validating that a vision LLM was even necessary.

**New direction:** Measure first, architect second.

A one-day validation spike in `bp-app/experiments/` tests three OCR engines against real BP-monitor photos. The spike result picks the architecture. No Android native code, no JNI, no GGUF downloads, no CMake edits — **until evidence demands them.**

---

## 2. Why

The real problem is reading three numeric fields (systolic / diastolic / pulse) off a 7-segment LCD. Committing to a 2.2 GB vision model for this is disproportionate when ~10 MB alternatives (ML Kit / MediaPipe Text Recognition, Tesseract with `letsgodigital` traineddata) exist and have never been tested on the target photos.

Additionally, 7-segment LCDs are a documented weakness of general-purpose VLMs. Assuming Qwen2.5-VL "has excellent OCR" is not the same as proving it reads this specific monitor reliably.

---

## 3. Tier 0 Spike — Shipped

Commit `6ed65e3` on `Comfac-Global-Group/bp-app`.

### Artefacts (all under `bp-app/experiments/`)

| File | Purpose |
|---|---|
| `test_tesseract.html` | Browser harness — Tesseract.js v5 with rotate90 / threshold / upscale variants |
| `test_mediapipe.html` | Browser harness — Google MediaPipe Tasks Vision text recognition |
| `test_gemini.py` | Python — Gemini 2.0 Flash vision API baseline |
| `test_tesseract_node.mjs` | Headless Node.js Tesseract runner for batch testing |
| `parse_and_score.py` | Aggregates `*_results.json` → `results.csv` + `SUMMARY.md` |
| `manifest.json` | Ground truth for 6 labelled BP-monitor photos |
| `README.md` | Run instructions + decision guide + constraints |

### Hard constraints honoured

- No CMake edits
- No JNI
- No NDK
- No GGUF bundling
- No SmolChat fork changes

All downstream work waits for Tier 0 results.

---

## 4. Preliminary Signal

Tesseract.js on photo `20260414_112450` (GT: 118 / 78 / 59):

| Variant | Result |
|---|---|
| `normal` (800 px resize) | 0 numbers detected |
| `rotate90` (800 px resize) | `[39, 2, 0]` — all wrong |

**Implication:** Tesseract.js alone is insufficient. MediaPipe and Gemini remain to be measured. This early result is exactly why the spike exists — we are now running on data, not on FDR assumptions.

---

## 5. How to Complete Tier 0

```bash
# 1. Tesseract (browser)
cd bp-app
python3 -m http.server 8080
# → http://localhost:8080/experiments/test_tesseract.html
#   pick "Bloodpressure Samples/", click Run, click Export JSON

# 2. MediaPipe (browser)
# → http://localhost:8080/experiments/test_mediapipe.html
#   same flow

# 3. Gemini (Python)
export GOOGLE_API_KEY="..."   # https://aistudio.google.com/app/apikey
pip install google-genai pillow
python3 experiments/test_gemini.py

# 4. Score all three
python3 experiments/parse_and_score.py
# → results.csv  +  SUMMARY.md
```

---

## 6. Decision Gate

Drive off `SUMMARY.md` from step 4:

| Best engine accuracy | Next step |
|---|---|
| ≥ 95 % digit, ≥ 80 % full-match | **Tier 1** — integrate winning engine into bp-app immediately |
| 80–95 % digit | **Tier 1 with preprocessing** — add rotate90 + retry logic, then integrate |
| 60–80 % digit | **Tier 1 + fallback UI** — engine + manual correction path |
| < 60 % digit | **Tier 2** — try `letsgodigital` traineddata, or adopt a cloud VLM |
| All engines < 50 % | Problem is upstream (lighting / angle / capture pipeline); fix the camera path before the OCR path |

Only a **Tier 2-Cloud-Fails** result resurrects the FDR's on-device VLM plan.

---

## 7. What This Costs vs the FDR

| | FDR plan (v0) | New direction (v1) |
|---|---|---|
| Time to first working OCR | 3–6 weeks | 1 day spike + 1–2 days integration |
| Code surface area | JNI + CMake + mtmd + Service + UI | HTML + JS in existing PWA |
| APK impact | +200–500 MB assets | 0 — stays a PWA |
| Kimi-implementable unsupervised | No (NDK/JNI too architecture-heavy) | Yes (self-contained harnesses) |
| Reversibility if result is bad | Low | High — experiments folder is disposable |
| Depends on libmtmd stability | Yes | No |

---

## 8. Status of Related Documents

| Document | State after this pivot |
|---|---|
| `vision-models-fdr.md` | Deprioritised. Kept as reference for Tier 2+ resurrection. Do not implement. |
| `vision-models-fdr-REVIEW.md` | Canonical rationale for the pivot. Read this before arguing to revive the FDR. |
| `repoanalysis.md` | Updated to note the FDR is unvalidated and downstream of evidence. |
| `bp-app/experiments/README.md` | Operational runbook for Tier 0. |
| **This file** | The direction marker — points at the above and at the current spike. |

---

## 9. Open Items

- [ ] Justin finalises the 6-photo labelled set in `manifest.json` (any additions/corrections).
- [ ] Kimi runs all three harnesses end-to-end, produces `SUMMARY.md`.
- [ ] Justin reviews `SUMMARY.md`, picks tier per §6.
- [ ] If Tier 1: Kimi integrates the winning engine into bp-app's `runOCR()`.
- [ ] If Tier 2 (cloud): decide privacy/offline trade-off with the user before shipping.
- [ ] Only if all of the above fail: reopen `vision-models-fdr.md`.

---

## 10. One-Line Summary

> **Prove OCR works with something small before building something big.**
