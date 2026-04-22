/*
 * Copyright (C) 2025 AMM Project
 */

package io.shubham0204.smollmandroid.ui.screens.vision_hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.shubham0204.smollmandroid.data.AppDB
import io.shubham0204.smollmandroid.llm.HttpService
import io.shubham0204.smollmandroid.llm.VisionLMManager
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.io.File

class VisionHubActivity : ComponentActivity() {

    private val visionLMManager: VisionLMManager by inject()
    private val appDB: AppDB by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmolLMAndroidTheme {
                VisionHubScreen(
                    visionLMManager = visionLMManager,
                    appDB = appDB,
                    onBack = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionHubScreen(
    visionLMManager: VisionLMManager,
    appDB: AppDB,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var serviceRunning by remember { mutableStateOf(false) }
    var modelLoaded by remember { mutableStateOf(visionLMManager.isModelLoaded) }
    var statusText by remember { mutableStateOf("Idle") }
    var modelPath by remember { mutableStateOf("") }
    var mmprojPath by remember { mutableStateOf("") }
    var dbVisionModels by remember { mutableStateOf(listOf<io.shubham0204.smollmandroid.data.LLMModel>()) }

    // Scan for vision model files on mount
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val filesDir = context.filesDir
            val ggufs = filesDir.listFiles()?.filter { it.name.endsWith(".gguf") } ?: emptyList()
            // Heuristic: find a pair where one file name contains "mmproj"
            val textModel = ggufs.find { !it.name.contains("mmproj", ignoreCase = true) }
            val mmproj = ggufs.find { it.name.contains("mmproj", ignoreCase = true) }
            if (textModel != null) modelPath = textModel.absolutePath
            if (mmproj != null) mmprojPath = mmproj.absolutePath

            // Also load vision models from database
            dbVisionModels = appDB.getModelsList().filter { it.isVisionModel }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AMM Vision Hub") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Service Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("HTTP: ${if (serviceRunning) "Running on 127.0.0.1:8765" else "Stopped"}")
                    Text("Model: ${if (modelLoaded) "Loaded" else "Not loaded"}")
                    Text("Last action: $statusText")
                }
            }

            // Model Files Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Vision Model Files",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Text model: ${modelPath.ifEmpty { "Not found" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "MMProj: ${mmprojPath.ifEmpty { "Not found" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Place .gguf files in the app's internal storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Registered Vision Models from DB
            if (dbVisionModels.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Registered Vision Models",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        dbVisionModels.forEach { model ->
                            val textExists = File(model.path).exists()
                            val mmprojExists = model.mmprojPath.isNotEmpty() && File(model.mmprojPath).exists()
                            Text(
                                text = model.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            )
                            Text(
                                text = "Text: ${if (textExists) "ready" else "missing"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (textExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "MMProj: ${if (mmprojExists) "ready" else "missing"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mmprojExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("HTTP Service")
                Switch(
                    checked = serviceRunning,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            HttpService.start(context)
                            serviceRunning = true
                            statusText = "Service started"
                        } else {
                            HttpService.stop(context)
                            serviceRunning = false
                            statusText = "Service stopped"
                        }
                    }
                )
            }

            Button(
                onClick = {
                    if (modelPath.isEmpty() || mmprojPath.isEmpty()) {
                        statusText = "Model files not found"
                        return@Button
                    }
                    scope.launch(Dispatchers.IO) {
                        statusText = "Loading model..."
                        val result = visionLMManager.loadModel(modelPath, mmprojPath)
                        withContext(Dispatchers.Main) {
                            modelLoaded = result.isSuccess
                            statusText = if (result.isSuccess) "Model loaded" else "Load failed: ${result.exceptionOrNull()?.message}"
                        }
                    }
                },
                enabled = !modelLoaded && modelPath.isNotEmpty() && mmprojPath.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Vision Model")
            }

            Button(
                onClick = {
                    visionLMManager.unload()
                    modelLoaded = false
                    statusText = "Model unloaded"
                },
                enabled = modelLoaded,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unload Model")
            }

            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "bp-app Integration",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1. Download a vision-capable GGUF model + its mmproj file",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "2. Place both files in the app storage (use Import Model)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "3. Load the model and start the HTTP service",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "4. Open bp-app and capture a BP monitor photo",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
