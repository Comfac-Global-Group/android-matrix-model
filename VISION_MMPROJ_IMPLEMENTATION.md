# Vision Model MMProj Support — Implementation Notes

**Date:** 2026-04-22  
**Repo:** `android-matrix-model` (AMM / SmolChat-Android fork)  
**Branch:** `main`  
**Related Issue:** QA — GGUF mmproj file not downloading when downloading vision models from HuggingFace

---

## Problem Statement

Vision-capable GGUF models (e.g., Qwen2-VL, SmolVLM, Gemma 4V) require **two files**:
1. `model-text.gguf` — the language model
2. `model-mmproj.gguf` — the vision encoder / projector

The original SmolChat-Android codebase was built for **text-only chat** and treated a "model" as exactly one `.gguf` file. Consequently:
- The HuggingFace browser only showed `.gguf` files without distinguishing text models from mmproj files
- The download manager only enqueued **one** file per tap
- The Room database `LLMModel` entity had no fields for mmproj URL/path
- The import flow only copied a single file
- `VisionHubActivity` assumed users side-loaded both files via ADB/file manager

---

## Changes Made

### 1. Database Schema (`data/ModelsDB.kt`, `data/AppDB.kt`)

**Added fields to `LLMModel`:**
```kotlin
var mmprojUrl: String = ""
var mmprojPath: String = ""
var isVisionModel: Boolean = false
```

**Migration `MIGRATION_2_3`** adds the three columns to the existing `LLMModel` table. The database version is bumped from **2 → 3**.

**Updated `AppDB.addModel()`** signature to accept the new optional fields:
```kotlin
fun addModel(
    name: String,
    url: String,
    path: String,
    contextSize: Int,
    chatTemplate: String,
    mmprojUrl: String = "",
    mmprojPath: String = "",
    isVisionModel: Boolean = false,
)
```

---

### 2. HuggingFace Model Browser (`DownloadModelsViewModel.kt`)

`fetchModelInfoAndTree()` now **splits** the repo file tree into two lists instead of one undifferentiated filter:

```kotlin
val textModels = modelTree.filter {
    it.path.endsWith(".gguf") && !it.path.contains("mmproj", ignoreCase = true)
}
val mmprojFiles = modelTree.filter {
    it.path.endsWith(".gguf") && it.path.contains("mmproj", ignoreCase = true)
}
```

**New methods:**
- `downloadVisionModel(textModelUrl, mmprojUrl)` — enqueues two `DownloadManager` requests
- `copyVisionModelFile(textModelUri, mmprojUri, onComplete)` — copies both files into `context.filesDir` and registers a single `LLMModel` row with `isVisionModel = true`

---

### 3. Model Detail Screen (`ViewHFModelScreen.kt`)

- Accepts a new `mmprojFileTree: List<HFModelTree.HFModelFile>` parameter
- Displays **two sections**: "GGUF Files" (text models) and "MMProj Files (Vision Models)"
- Each mmproj file is shown as a **selectable card with a Checkbox**
- When the user taps a text model, the download dialog now passes **both URLs** to `onDownloadModel(textUrl, mmprojUrl?)`
- If an mmproj is selected, both files are downloaded; otherwise only the text model is downloaded

**Callback signature changed:**
```kotlin
// Before
onDownloadModel: (String) -> Unit

// After
onDownloadModel: (String, String?) -> Unit
```

---

### 4. Navigation Route (`DownloadModelActivity.kt`, `CustomNavTypes.kt`)

`ViewModelRoute` now carries both file lists:
```kotlin
data class ViewModelRoute(
    val modelId: String,
    val modelInfo: HFModelInfo.ModelInfo,
    val modelFiles: List<HFModelTree.HFModelFile>,
    val mmprojFiles: List<HFModelTree.HFModelFile>,   // NEW
)
```

`CustomNavTypes` already supports `List<HFModelTree.HFModelFile>`, so no new NavType was needed.

---

### 5. Model Import (`ImportModelScreen.kt`)

Added a **two-file import flow** for vision models alongside the existing single-file import:

1. **Single file import** (existing) — unchanged
2. **Vision model import** (new):
   - Button: "Select Text Model .gguf"
   - After selection, button appears: "Select MMProj .gguf (optional)"
   - Finally: "Import Vision Model" button
   - Calls `copyVisionModelFile(textUri, mmprojUri?)` which writes both files and stores `isVisionModel = true`

---

### 6. Model Selector (`SelectModelsList.kt`)

Each model list item now shows:
- **Eye icon** (`FeatherIcons.Eye`) next to the name when `isVisionModel == true`
- **MMProj status label** below the file size:
  - `"MMProj: ready"` (primary color) when `mmprojPath` exists on disk
  - `"MMProj: missing"` (error color) when absent

This gives users immediate visual feedback about whether a vision model is actually usable.

---

### 7. Vision Hub (`VisionHubActivity.kt`)

- Injects `AppDB` to query registered vision models
- After the existing heuristic file scan, a new **"Registered Vision Models"** card appears if any `LLMModel` rows have `isVisionModel = true`
- Lists each model with:
  - Text model file status (`ready` / `missing`)
  - MMProj file status (`ready` / `missing`)

This bridges the gap between the chat-oriented model registry and the vision hub's file-based loader.

---

### 8. String Resources (`values/strings.xml`)

New strings added:
```xml
<string name="vision_model_import_text">Select Text Model .gguf</string>
<string name="vision_model_import_mmproj">Select MMProj .gguf (optional)</string>
<string name="vision_model_import_button">Import Vision Model</string>
<string name="vision_model_mmproj_ready">MMProj: ready</string>
<string name="vision_model_mmproj_missing">MMProj: missing</string>
<string name="download_model_mmproj_title">MMProj Files (Vision Models)</string>
```

---

## Files Modified

| File | Change |
|------|--------|
| `data/ModelsDB.kt` | Added `mmprojUrl`, `mmprojPath`, `isVisionModel` to `LLMModel` |
| `data/AppDB.kt` | Added `MIGRATION_2_3`, bumped DB version to 3, updated `addModel()` |
| `ui/screens/model_download/DownloadModelsViewModel.kt` | Split file tree, added `downloadVisionModel()`, `copyVisionModelFile()` |
| `ui/screens/model_download/ViewHFModelScreen.kt` | Added mmproj list UI with checkbox, updated download dialog |
| `ui/screens/model_download/DownloadModelActivity.kt` | Updated `ViewModelRoute` and navigation callbacks |
| `ui/screens/model_download/ImportModelScreen.kt` | Added two-file vision model import flow |
| `ui/components/SelectModelsList.kt` | Added eye icon and mmproj status to model list items |
| `ui/screens/vision_hub/VisionHubActivity.kt` | Added DB scan and registered vision model status card |
| `res/values/strings.xml` | Added new UI strings |

---

## Testing Checklist

- [ ] Existing text-only models continue to download and load normally
- [ ] Room migration 2→3 runs without crash on app upgrade
- [ ] HF model browser shows separate "GGUF Files" and "MMProj Files" sections
- [ ] Tapping a text model with mmproj checked downloads both files
- [ ] Tapping a text model with mmproj unchecked downloads only the text model
- [ ] Importing a vision model pair registers `isVisionModel = true` in DB
- [ ] Model selector shows eye icon and correct mmproj status
- [ ] Vision Hub lists registered models with correct file existence status
- [ ] Vision Hub load button is disabled when mmproj is missing

---

## Future Work

- **Auto-detect mmproj pairing** by filename similarity instead of relying on user selection
- **Settings → Models** screen with a dedicated "Download missing mmproj" action per model
- **Popular models list** update to include a vision model (e.g., Qwen2-VL-2B-Instruct) with pre-filled mmproj URL
- **Delete model** cascade: when deleting a vision model, also delete the associated mmproj file

---

## Root Cause (Reiterated)

> *Could it be SmolChat was purely chat and wasn't equipped to download vision models and models with other abilities?*

**Yes.** Every layer of the model-management stack — Room schema, DownloadManager wrapper, HF file tree parser, import flow, and popular-models list — was designed for **one model = one `.gguf` file**. The vision inference code (`VisionLMManager`, `VisionHubActivity`) was added later but the **infrastructure that gets models onto the device** was never extended. That is why the mmproj file was silently ignored: the app literally had no concept of it.
