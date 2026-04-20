# 260420-01 — AMM Rebrand (Tier 1 / 2 / 3) + In-App Vision Test Plan

**Date:** 2026-04-20
**Repo:** `android-matrix-model` (**fork of [shubham0204/SmolChat-Android](https://github.com/shubham0204/SmolChat-Android)**)
**Status:** 📋 Planned — awaiting tier selection before execution
**Authoritative upstream:** `shubham0204/SmolChat-Android`
**Companion docs:**
- `260419-v02 platform-plan-selfhosted-ai-hub.md` — platform framing (AMM as on-device AI hub)
- `vision-models-fdr.md` — implementation reference for the vision stack
- `260419-v01 vision-OCR-new-direction.md` — cheap-OCR spike gate

---

## 0. Upstream Attribution — Standing Rule

**AMM (Android Matrix Models) is a fork of SmolChat-Android by shubham0204.**

This attribution is not optional. Every rebranded surface must, at minimum:

- Keep the original LICENSE text and NOTICE attribution intact.
- Keep a visible "Forked from shubham0204/SmolChat-Android" line in:
  - `README.md` (top, under the title)
  - `PRIVACY_POLICY.md` (header)
  - An in-app "About" screen (added in Tier 1) listing upstream repo + commit SHA.
- Never claim authorship of the SmolChat codebase. AMM owns the fork-specific changes only (vision JNI, HTTP service, platform features per `260419-v02`).
- Keep the `smollm` module name on disk (see Tier 3 note) even after the Android package rename — renaming it breaks `git log --follow` against upstream.

When in doubt: add the attribution, don't remove it.

---

## 1. Why Three Tiers

The user-visible pain is: **debug builds install with the label "SmolChat"**. The root cause touches up to five layers:

| Layer | SmolChat reference | User-visible? |
|---|---|---|
| 1. `strings.xml` `app_name` | "SmolChat" | ✅ launcher icon label |
| 2. Store metadata (`metadata/en-US/*.txt`) | "SmolChat - Run LLMs locally…" | ✅ F-Droid / Obtainium listing |
| 3. Android `applicationId` | `io.shubham0204.smollmandroid` | ⚠️ only in Settings → Apps and adb |
| 4. Kotlin classes (`SmolChatApplication`, `SmolLMManager`, etc.) + source tree `io/shubham0204/smollmandroid/…` | internal | ❌ code-only |
| 5. JNI package + native symbols (`Java_io_shubham0204_smollm_SmolLM_*` in `smollm.cpp`, Java package `io.shubham0204.smollm`) | internal | ❌ crashes if mismatched |

Each tier covers more layers:

- **Tier 1** = Layer 1 + 2 (user-visible only)
- **Tier 2** = Layer 1 + 2 + debug-flavour suffix on Layer 3 (side-by-side installs, clear debug label)
- **Tier 3** = Layers 1–5 (full fork identity)

**Tier 3 is a one-way door** — it permanently ends cheap `git pull` from upstream. Every upstream change afterward is a manual cherry-pick. Only do Tier 3 when you've committed to AMM as its own product.

---

## 2. Tier 1 — User-Visible Rebrand (5 min, zero risk)

Goal: launcher says "AMM"; store listings say "Android Matrix Models". Everything else keeps the SmolChat internal identity.

### 2.1 Files to change

| File | Change |
|---|---|
| `app/src/main/res/values/strings.xml` | `<string name="app_name">AMM</string>` |
| `app/src/main/res/values-pt/strings.xml` | `<string name="app_name">AMM</string>` |
| `app/src/main/res/values-zh-rCN/strings.xml` | `<string name="app_name">AMM</string>` |
| `metadata/en-US/title.txt` | `Android Matrix Models — on-device AI hub` |
| `metadata/en-US/short_description.txt` | `Self-hosted on-device AI (vision, LLM, ASR). Forked from SmolChat.` |
| `metadata/en-US/full_description.txt` | Rewrite around the platform plan. Keep the "Forked from SmolChat-Android by shubham0204" line at the end. |
| `README.md` (title + first line) | `# AMM — Android Matrix Models` · `> A fork of [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) repositioned as a self-hosted on-device AI hub.` |
| `PRIVACY_POLICY.md` (header) | Same attribution line |

### 2.2 Do NOT change

- `applicationId` (`io.shubham0204.smollmandroid`) — keep it so installed APKs upgrade cleanly over debug/release boundary.
- `namespace`, Kotlin class names, JNI symbols, source tree paths.
- Launcher icon (unless the user supplies a new one separately).

### 2.3 Verification

```bash
./gradlew :app:assembleRelease
# Install, check launcher label = "AMM"
# Settings → Apps → "AMM" (app-ID still shows io.shubham0204.smollmandroid — that's fine at Tier 1)
```

### 2.4 Rollback

`git revert` the tier-1 commit. Zero runtime risk — strings only.

---

## 3. Tier 2 — Tier 1 + Debug Flavour Separation (15 min, zero runtime risk)

Goal: debug builds install **alongside** release with a visibly different label ("AMM Debug"), so "the debug mode calls itself smolchat" problem disappears entirely.

Do everything in Tier 1 first. Then:

### 3.1 Add a debug `buildType` suffix

`app/build.gradle.kts` — inside the existing `buildTypes { … }` block:

```kotlin
buildTypes {
    getByName("release") {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        signingConfig = signingConfigs.getByName("release")
    }
    getByName("debug") {
        applicationIdSuffix = ".debug"
        versionNameSuffix = "-debug"
        isDebuggable = true
        // Debug strings live in app/src/debug/res — see §3.2
    }
}
```

Result: debug APK installs as `io.shubham0204.smollmandroid.debug` — side-by-side with the release `io.shubham0204.smollmandroid`.

### 3.2 Add a debug source-set `strings.xml`

Create `app/src/debug/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AMM Debug</string>
</resources>
```

Android merges source sets by build variant — the debug value overrides Tier 1's `app_name=AMM` only in debug builds. Release builds are unaffected.

### 3.3 Optional — debug launcher icon tint

If you want the icon itself to look different (strongly recommended on devices where debug and release coexist), drop a modified `ic_launcher.png` into `app/src/debug/res/mipmap-*`. A red dot overlay is conventional.

### 3.4 Verification

```bash
./gradlew :app:installDebug
# Launcher shows "AMM Debug"
# adb shell pm list packages | grep smollmandroid
#   io.shubham0204.smollmandroid.debug  ← debug
#   io.shubham0204.smollmandroid        ← release (if also installed)

./gradlew :app:installRelease   # still shows "AMM", installs alongside
```

### 3.5 Rollback

Revert the `build.gradle.kts` edit and delete `app/src/debug/`. No data migration needed.

### 3.6 Notes

- The upstream sync impact is nil — this is purely additive build config.
- If the upstream ever adds its own `debug` buildType block, the merge is a three-way text conflict in `app/build.gradle.kts` — trivial to resolve.

---

## 4. Tier 3 — Full Package Rename (hours, high blast radius)

Goal: `applicationId`, Kotlin package tree, JNI Java package, and native symbols all become AMM. The app has no remaining SmolChat identifiers except upstream-attribution strings.

**Prerequisite decisions (confirm before starting):**

1. **New Android application ID.** Proposed: `io.gi7b.amm` (Game-in-the-Brain) or `com.comfac.amm` (Comfac-IT). Must be globally unique and is permanent once published.
2. **New Kotlin package roots.**
   - App module: `io.shubham0204.smollmandroid` → `io.gi7b.amm`
   - Library module `smollm`: `io.shubham0204.smollm` → `io.gi7b.amm.llm` (keeps the `smollm` Gradle module name on disk so `git log --follow smollm/…` still works against upstream — see §0)
3. **Will you still pull from upstream?** If yes, stop here and do Tier 2 instead. Tier 3 ends upstream sync.

### 4.1 Execution order (must be in this order)

Out-of-order edits cause `UnsatisfiedLinkError` at runtime or compilation failure in the middle, leaving the tree half-renamed.

```
Step 1 — Android Studio assisted Kotlin/Java rename
Step 2 — Move source directories on disk
Step 3 — Update Gradle namespaces + applicationId
Step 4 — Rename JNI native symbols
Step 5 — Update AndroidManifest entries
Step 6 — Full-text search sweep for stragglers
Step 7 — Clean build + instrumentation test
```

### 4.2 Step 1 — Kotlin/Java class renames

Use Android Studio's **Refactor → Rename** (Shift-F6) for each. These auto-update every call site + imports:

| From | To |
|---|---|
| `SmolChatApplication` | `AmmApplication` |
| `SmolLMManager` | `AmmLlmManager` |
| `SmolLM` (in `smollm` module) | `AmmLlm` — **critical: see §4.4 — renaming this class changes JNI symbols** |

### 4.3 Step 2 — Source-tree move

Use **Refactor → Move Package**:

| From | To |
|---|---|
| `app/src/main/java/io/shubham0204/smollmandroid/` | `app/src/main/java/io/gi7b/amm/` |
| `app/src/test/java/io/shubham0204/smollmandroid/` | `app/src/test/java/io/gi7b/amm/` |
| `app/src/androidTest/java/io/shubham0204/smollmandroid/` | `app/src/androidTest/java/io/gi7b/amm/` |
| `smollm/src/main/java/io/shubham0204/smollm/` | `smollm/src/main/java/io/gi7b/amm/llm/` |
| `smollm/src/androidTest/java/io/shubham0204/smollm/` | `smollm/src/androidTest/java/io/gi7b/amm/llm/` |

**Do not rename the Gradle module folders** (`smollm/`, `smolvectordb/`, `hf-model-hub-api/`). They can keep their SmolChat-era names; changing them forces updates to `settings.gradle.kts` and breaks upstream `git log --follow`.

### 4.4 Step 3 — Gradle namespace + applicationId

`app/build.gradle.kts`:

```kotlin
android {
    namespace = "io.gi7b.amm"                // was io.shubham0204.smollmandroid
    defaultConfig {
        applicationId = "io.gi7b.amm"        // was io.shubham0204.smollmandroid
        // ...
    }
}
```

`smollm/build.gradle.kts`:

```kotlin
android {
    namespace = "io.gi7b.amm.llm"            // was io.shubham0204.smollm
}
```

### 4.5 Step 4 — JNI native symbol rename ⚠️ HIGHEST-RISK STEP

JNI bindings are resolved by **exact symbol name** matching the Java class path. If Step 2 moved `SmolLM` to `io.gi7b.amm.llm.AmmLlm`, every native symbol in `smollm/src/main/cpp/smollm.cpp` must change from:

```cpp
Java_io_shubham0204_smollm_SmolLM_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, ...)
```

to:

```cpp
Java_io_gi7b_amm_llm_AmmLlm_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, ...)
```

**All symbols to rename** (current list at `smollm.cpp`):

| Old symbol prefix | New symbol prefix |
|---|---|
| `Java_io_shubham0204_smollm_SmolLM_` | `Java_io_gi7b_amm_llm_AmmLlm_` |

Full list of methods to update (from current `smollm.cpp`):
`loadModel`, `addChatMessage`, `getResponseGenerationSpeed`, `getContextSizeUsed`, `close`, `startCompletion`, `completionLoop`, `stopCompletion`, `benchModel` (+ any added since — re-grep before editing).

Plus any other native methods in `GGUFReader.cpp` whose Java class moved.

**Verification of Step 4 (do not skip):**

```bash
# After the rename, grep should return ZERO hits for old symbols:
rg "Java_io_shubham0204" smollm/src/main/cpp/
# And the count of Java_io_gi7b_amm_llm_AmmLlm_* symbols must equal
# the count of external "native fun ..." declarations in AmmLlm.kt:
rg -c "^Java_io_gi7b_amm_llm_AmmLlm_" smollm/src/main/cpp/
rg -c "external fun" smollm/src/main/java/io/gi7b/amm/llm/AmmLlm.kt
# The two numbers must match.
```

### 4.6 Step 5 — AndroidManifest

`app/src/main/AndroidManifest.xml`:

```xml
<application
    android:name=".AmmApplication"        <!-- was .SmolChatApplication -->
    ...>
```

Any other fully-qualified class references in the manifest (providers, receivers, services added by the vision-service work in `260419-v02`) get updated accordingly.

### 4.7 Step 6 — Full-text sweep

These identifiers will have crept into places Android Studio's refactor tool can't see: ProGuard rules, Kotlin DSL strings, comments, READMEs, test fixtures.

```bash
# Inside android-matrix-model/ :
rg --files-with-matches "shubham0204|SmolChat|smollmandroid|SmolLM\b|SmolLMManager|SmolChatApplication" \
   --glob '!node_modules/**' --glob '!llama.cpp/**' --glob '!.git/**' \
   | while read f; do
       echo "=== $f ==="; rg -n "shubham0204|SmolChat|smollmandroid|SmolLM\b|SmolLMManager|SmolChatApplication" "$f"
     done
```

Expected legitimate survivors (do NOT rename these):
- `LICENSE` — original copyright lines
- Attribution lines in `README.md`, `PRIVACY_POLICY.md`, "About" screen
- Any doc that narrates history (`260419-v01`, `vision-models-fdr.md`, this doc)
- Commit messages in `git log` (immutable)

Everything else is a stale identifier.

### 4.8 Step 7 — Clean build + smoke test

```bash
./gradlew clean
./gradlew :app:assembleDebug
# Install on a physical device and:
# (a) Launch the app — if JNI symbols are mismatched, the app crashes on first LLM load
#     with UnsatisfiedLinkError — fix Step 4 before proceeding.
# (b) Load a text model; send one prompt; confirm completion streams.
# (c) Run ./gradlew :smollm:connectedAndroidTest — the instrumentation suite will
#     catch any dangling JNI references.
```

### 4.9 Tier 3 rollback

If Step 4 goes wrong mid-way: `git reset --hard` the branch you were working on. Do **not** attempt to hand-patch a half-renamed JNI layer; the symbol-matching problem is too unforgiving. Branch, commit per step, revert on failure.

### 4.10 After Tier 3 — upstream sync

Upstream sync becomes a manual cherry-pick process:

```bash
git remote add upstream https://github.com/shubham0204/SmolChat-Android
git fetch upstream
git log upstream/main --oneline -20      # find commits you want
git cherry-pick <sha>                    # resolve package-path conflicts by hand
```

Budget this into the AMM maintenance plan.

---

## 5. In-App Vision Test Feature (FRD Addition)

Independent of rebrand — once the vision JNI work from `260419-v02` Phase 1 lands, the app needs a **built-in test surface** so a user (or QA, or a client-app developer) can verify image recognition end-to-end without writing code.

### 5.1 FR-VT1 — Vision Test Screen (📋 Planned)

**Entry point:** a new drawer item "Vision Test" in the main navigation. Only visible when a vision model is loaded (or offer a one-tap download if not).

**UI:**

```
┌──────────────────────────────────────┐
│  ← Vision Test              [▼ Model]│   ← model selector
├──────────────────────────────────────┤
│  ┌────────────────────────┐          │
│  │                        │          │   ← image preview pane
│  │   [ 📷 Capture ]       │          │      (empty state shows capture/pick buttons)
│  │   [ 🖼 Pick from gallery ]        │
│  │                        │          │
│  └────────────────────────┘          │
│                                      │
│  Prompt (editable):                  │
│  ┌────────────────────────────────┐ │
│  │ Describe what you see.         │ │   ← defaults to "Describe what you see."
│  └────────────────────────────────┘ │      Preset dropdown: Generic / BP-monitor / OCR / Caption / Custom
│                                      │
│  Response schema (optional JSON):   │
│  ┌────────────────────────────────┐ │
│  │ { "sys": int, "dia": int, ... }│ │   ← only shown when schema mode is on
│  └────────────────────────────────┘ │
│                                      │
│  [        Run Inference        ]     │
├──────────────────────────────────────┤
│  Response:                           │
│  ┌────────────────────────────────┐ │
│  │ {"sys": 118, "dia": 78, ...}   │ │   ← streamed or final
│  └────────────────────────────────┘ │
│  Tokens: 42  ·  Time: 1.8 s          │   ← usage stats
│  [ Copy ]  [ Share ]  [ Save run ]   │
└──────────────────────────────────────┘
```

**Interaction:**

1. User picks or captures an image (reuses `ActivityResultContracts.TakePicture()` / `GetContent()`).
2. User edits or picks a preset prompt.
3. (Optional) User pastes a JSON schema. When present, the test runs with `response_schema` set and shows parse-pass or parse-fail in the response pane.
4. "Run Inference" calls the same `LLMInference::visionCompletion()` path that the HTTP service will use (see §5.2). UI subscribes to the streaming `Flow<String>`.
5. Response pane updates as tokens arrive; usage stats populate on completion.
6. "Save run" stores the `{image, prompt, schema, response, timing, modelId}` bundle in a local SQLite table so the user can build a small regression corpus without leaving the app.

### 5.2 FR-VT2 — Shared Inference Path (📋 Planned)

Critical invariant: **the Vision Test screen and the HTTP service call the exact same `LLMInference::visionCompletion()`**. If a prompt works in Vision Test, it works over HTTP. Do not duplicate the code path.

```
              ┌──────────────────────────┐
              │  LLMInference (C++)       │
              │  visionCompletion()       │
              └──────────┬───────────────┘
                         │ JNI
              ┌──────────┴───────────────┐
              │  AmmLlm.kt                │   (Tier 3 rename)
              │  getVisionResponseFlow()  │
              └──────────┬───────────────┘
                ┌────────┴────────┐
                │                 │
      Vision Test Screen    HTTP Service
      (Compose UI)          (NanoHTTPD on :8765)
```

### 5.3 FR-VT3 — Saved Runs & Regression (🟠 Stretch)

A "Runs" tab on the Vision Test screen shows saved runs with filter chips:

- By model
- By prompt preset
- By pass/fail against stored expected JSON
- By date

Export: zip of `{image.jpg, run.json}` pairs for taking off-device into bp-app's `experiments/` harness.

Import: accept the same zip shape so saved runs travel between devices.

### 5.4 FR-VT4 — BP-Specific Preset (📋 Planned — bridges to `bp-app`)

Pre-loaded prompt + schema to sanity-check that an Omron HEM-7121 photo produces clean `{sys,dia,pulse}` without bp-app being involved.

```
Prompt preset: "BP Monitor (Omron HEM-7121)"
Schema: { "sys": int, "dia": int, "pulse": int }
Prompt body:
  Read the blood pressure monitor display carefully.
  The top number is systolic (SYS), middle is diastolic (DIA), bottom is pulse (PULSE).
  These are 7-segment LCD digits. Pay attention to thin strokes.
  Return JSON only. No prose.
```

Ship the 6 labelled photos from `bp-app/experiments/` as an optional asset bundle (download, not bundled in APK) so the user can hit "Run on built-in BP test set" and see the accuracy number directly.

---

## 6. Planned Actions

Ordered. Check off as completed.

### 6.1 Rebrand

- [ ] **Decide** — Tier 1, Tier 2, or Tier 3. Record the decision in the PR description.
- [ ] **Tier 1** (if chosen):
  - [ ] Edit three `strings.xml` files → `app_name = "AMM"`
  - [ ] Rewrite `metadata/en-US/title.txt`, `short_description.txt`, `full_description.txt`
  - [ ] Add "Forked from SmolChat-Android" line to `README.md` and `PRIVACY_POLICY.md`
  - [ ] Build release APK; verify launcher label
- [ ] **Tier 2** (if chosen — do Tier 1 first):
  - [ ] Add `debug` buildType with `applicationIdSuffix = ".debug"` in `app/build.gradle.kts`
  - [ ] Create `app/src/debug/res/values/strings.xml` with `app_name = "AMM Debug"`
  - [ ] (Optional) Add tinted debug launcher icon under `app/src/debug/res/mipmap-*`
  - [ ] Build debug + release side-by-side; verify both labels + both app-IDs
- [ ] **Tier 3** (if chosen — do Tier 1 and Tier 2 first):
  - [ ] Lock in the new application ID (`io.gi7b.amm` proposed) and new Kotlin package roots
  - [ ] Create a branch `rebrand/tier-3-full-rename`, commit per §4 step
  - [ ] Step 1 — Android Studio Rename on `SmolChatApplication`, `SmolLMManager`, `SmolLM`
  - [ ] Step 2 — Move packages under `app/src/{main,test,androidTest}/java/` and `smollm/src/{main,androidTest}/java/`
  - [ ] Step 3 — `namespace` + `applicationId` in both `build.gradle.kts` files
  - [ ] Step 4 — rewrite every `Java_io_shubham0204_smollm_SmolLM_*` symbol in `smollm/src/main/cpp/smollm.cpp` (and `GGUFReader.cpp` if affected). Run the §4.5 verification greps.
  - [ ] Step 5 — update `AndroidManifest.xml` class references
  - [ ] Step 6 — run the §4.7 full-text sweep; clean up stragglers; leave attribution lines alone
  - [ ] Step 7 — `./gradlew clean` + `:app:assembleDebug` + `:smollm:connectedAndroidTest` on a device
  - [ ] Add upstream as a Git remote and document the cherry-pick workflow in `docs/upstream-sync.md`
- [ ] **Regardless of tier**: add an in-app **About** screen showing:
  - AMM version
  - Upstream: `shubham0204/SmolChat-Android @ <pinned commit SHA>`
  - Fork-specific feature list (links to `260419-v02` + this doc)

### 6.2 Vision Test Feature

- [ ] FR-VT1 — Vision Test Screen (blocked on `260419-v02` Phase 1 vision JNI)
- [ ] FR-VT2 — Shared `LLMInference::visionCompletion()` path used by both UI and HTTP service
- [ ] FR-VT4 — BP-monitor preset + schema
- [ ] FR-VT3 — Saved runs, filter, export/import (stretch)

### 6.3 Cross-cutting

- [ ] Update `repoanalysis.md` after Tier 1 merge to note AMM identity + upstream attribution
- [ ] Update `README.md` to lead with "AMM — Android Matrix Models (fork of SmolChat-Android)"
- [ ] Update `260419-v02 platform-plan-selfhosted-ai-hub.md` §2.1 to use the chosen application ID
- [ ] Add a one-line attribution to every new doc created after this point

---

## 7. Risks

| Risk | Tier | Mitigation |
|---|---|---|
| Debug + release collide on device (same applicationId) | 1 | Ship Tier 2 as soon as Tier 1 is in — `applicationIdSuffix` fixes it |
| Translation strings drift (`values-pt`, `values-zh-rCN` forgotten) | 1 | Sweep `rg "SmolChat" app/src/main/res/` after Tier 1 |
| JNI UnsatisfiedLinkError at runtime | 3 | §4.5 grep verification — both counts must match before first build |
| Merge conflicts on next upstream sync | 3 | This is expected; document the cherry-pick workflow before anyone else on the team pulls upstream |
| User confuses "AMM Debug" with "AMM" and logs bugs against the wrong build | 2 | Tinted debug icon (§3.3) reduces confusion substantially |
| Accidental removal of SmolChat attribution in rebrand enthusiasm | all | §0 is the standing rule; add a CI grep to fail the build if `LICENSE` or the fork-attribution line disappears |

---

## 8. Decision Log

| Date | Decision | Rationale |
|---|---|---|
| 2026-04-20 | Three-tier plan established | Full rename is high-blast-radius; user's real pain is the debug label; offering tiers lets the user pick the right scope |
| 2026-04-20 | Upstream attribution is a standing rule | AMM is a fork, not a from-scratch project; attribution protects licence compliance and fairness to `shubham0204` |
| 2026-04-20 | In-app Vision Test surface added | Test-in-app is the cheapest way to validate vision end-to-end before any client app (bp-app, doc indexer) integrates |
| 2026-04-20 | Vision Test and HTTP service share one C++ entry point | Prevents drift — "works in the test screen but not over HTTP" bugs become impossible |
