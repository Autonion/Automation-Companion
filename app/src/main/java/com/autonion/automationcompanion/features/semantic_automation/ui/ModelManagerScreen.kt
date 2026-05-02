package com.autonion.automationcompanion.features.semantic_automation.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ModelStorageManager
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiConnectionStatus
import com.autonion.automationcompanion.features.semantic_automation.ml.CLOUD_API_PROVIDERS
import com.autonion.automationcompanion.features.semantic_automation.consent.CloudApiConsentManager
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import kotlinx.coroutines.launch
import java.io.File

// ─── Model Catalog ───────────────────────────────────────────

data class SLMModelInfo(
    val name: String,
    val parameterCount: String,      // e.g. "2B", "7B"
    val sizeGb: Double,              // Download size in GB
    val minRamGb: Double,            // Minimum RAM to run
    val recommendedRamGb: Double,    // Recommended RAM for smooth performance
    val quantization: String,        // e.g. "INT4", "INT8", "FP16"
    val runtime: String,             // e.g. "CPU", "GPU"
    val downloadUrl: String,
    val source: String,              // e.g. "Kaggle", "HuggingFace"
    val description: String
)

private val MODEL_CATALOG = listOf(
    SLMModelInfo(
        name = "Gemma 2B IT",
        parameterCount = "2B",
        sizeGb = 1.35,
        minRamGb = 4.0,
        recommendedRamGb = 6.0,
        quantization = "INT4",
        runtime = "CPU",
        downloadUrl = "https://www.kaggle.com/models/google/gemma/tfLite/gemma-2b-it-cpu-int4",
        source = "Kaggle",
        description = "Lightweight, fast. Best for devices with limited RAM."
    ),
    SLMModelInfo(
        name = "Gemma 2B IT (GPU)",
        parameterCount = "2B",
        sizeGb = 1.35,
        minRamGb = 4.0,
        recommendedRamGb = 6.0,
        quantization = "INT4",
        runtime = "GPU",
        downloadUrl = "https://www.kaggle.com/models/google/gemma/tfLite/gemma-2b-it-gpu-int4",
        source = "Kaggle",
        description = "Same as CPU variant but accelerated on GPU. Faster inference."
    ),
    SLMModelInfo(
        name = "Gemma 7B IT",
        parameterCount = "7B",
        sizeGb = 3.8,
        minRamGb = 8.0,
        recommendedRamGb = 12.0,
        quantization = "INT4",
        runtime = "CPU",
        downloadUrl = "https://www.kaggle.com/models/google/gemma/tfLite/gemma-7b-it-cpu-int4",
        source = "Kaggle",
        description = "Significantly smarter reasoning. Needs 8GB+ RAM."
    ),
    SLMModelInfo(
        name = "Gemma 7B IT (GPU)",
        parameterCount = "7B",
        sizeGb = 3.8,
        minRamGb = 8.0,
        recommendedRamGb = 12.0,
        quantization = "INT4",
        runtime = "GPU",
        downloadUrl = "https://www.kaggle.com/models/google/gemma/tfLite/gemma-7b-it-gpu-int4",
        source = "Kaggle",
        description = "Best quality + GPU acceleration. For flagship devices."
    ),
    SLMModelInfo(
        name = "Gemma 2 2B IT",
        parameterCount = "2B",
        sizeGb = 1.4,
        minRamGb = 4.0,
        recommendedRamGb = 6.0,
        quantization = "INT4",
        runtime = "CPU",
        downloadUrl = "https://www.kaggle.com/models/google/gemma-2/tfLite/gemma2-2b-it-cpu-int4",
        source = "Kaggle",
        description = "Next-gen Gemma 2. Better reasoning than Gemma 1 at same size."
    ),
    SLMModelInfo(
        name = "Phi-2",
        parameterCount = "2.7B",
        sizeGb = 1.6,
        minRamGb = 4.0,
        recommendedRamGb = 6.0,
        quantization = "INT4",
        runtime = "CPU",
        downloadUrl = "https://huggingface.co/microsoft/phi-2",
        source = "HuggingFace",
        description = "Microsoft's compact model. Strong code & reasoning skills."
    )
)

// ─── Screen ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    storageManager: ModelStorageManager,
    localServerEngine: LocalServerLLMEngine,
    inferenceMode: SemanticAutomationEngine.InferenceMode,
    onInferenceModeChanged: (SemanticAutomationEngine.InferenceMode) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var importedModels by remember { mutableStateOf(storageManager.getImportedModels()) }
    var activeModelPath by remember { mutableStateOf(storageManager.getActiveModelPath()) }
    var isImporting by remember { mutableStateOf(false) }

    // Server LLM state
    val connectionStatus by localServerEngine.connectionStatus.collectAsState()
    val availableModels by localServerEngine.availableModels.collectAsState()
    val serverUrl by localServerEngine.serverUrl.collectAsState()
    val selectedModel by localServerEngine.selectedModelName.collectAsState()
    var manualIpInput by remember { mutableStateOf(serverUrl.ifBlank { "" }) }
    var showModelDropdown by remember { mutableStateOf(false) }

    // Cross-device auto-discovery status
    val crossDeviceManager = remember { CrossDeviceAutomationManager.getInstance(context) }
    val isCrossDeviceEnabled = remember { crossDeviceManager.isFeatureEnabled() }
    
    // Auto-fill IP from cross-device discovery if connected
    val connectedDevices by crossDeviceManager.deviceRepository.getAllDevices().collectAsState(initial = emptyList())
    LaunchedEffect(connectedDevices) {
        val onlineDevice = connectedDevices.firstOrNull { it.status == com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus.ONLINE }
        if (onlineDevice != null && manualIpInput.isBlank() && serverUrl.isBlank()) {
            val ip = onlineDevice.ipAddress
            manualIpInput = ip
            val newUrl = "http://$ip:11434"
            localServerEngine.setServerUrl(newUrl)
            localServerEngine.initialize()
        }
    }

    // Hardware info
    val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    actManager.getMemoryInfo(memInfo)
    val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)

    // Cloud API state
    val cloudApiEngine = remember { CloudApiLLMEngine.getInstance(context) }
    val cloudConnectionStatus by cloudApiEngine.connectionStatus.collectAsState()
    val cloudErrorMessage by cloudApiEngine.errorMessage.collectAsState()
    val selectedCloudProvider by cloudApiEngine.selectedProvider.collectAsState()
    var apiKeyInput by remember { mutableStateOf(cloudApiEngine.apiKey) }
    var modelNameInput by remember { mutableStateOf(cloudApiEngine.modelName) }
    var customBaseUrlInput by remember { mutableStateOf(cloudApiEngine.baseUrl) }
    var showApiKey by remember { mutableStateOf(false) }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showCloudModelDropdown by remember { mutableStateOf(false) }
    var fetchedCloudModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingCloudModels by remember { mutableStateOf(false) }

    LaunchedEffect(cloudConnectionStatus, selectedCloudProvider, customBaseUrlInput, apiKeyInput) {
        val isOllamaProvider = selectedCloudProvider.id == "ollama"
        val isOllamaCustom = selectedCloudProvider.id == "custom" && customBaseUrlInput.contains("ollama.com", ignoreCase = true)
        val isOllamaUrl = selectedCloudProvider.baseUrl.contains("ollama.com", ignoreCase = true)

        if ((isOllamaProvider || isOllamaCustom || isOllamaUrl) && apiKeyInput.isNotBlank()) {
            // For Ollama Cloud, fetch models as soon as API key is present — don't require CONNECTED
            isFetchingCloudModels = true
            fetchedCloudModels = cloudApiEngine.getAvailableModels() ?: emptyList()
            isFetchingCloudModels = false
        } else if (cloudConnectionStatus == CloudApiConnectionStatus.CONNECTED &&
            (isOllamaCustom || isOllamaUrl)
        ) {
            isFetchingCloudModels = true
            fetchedCloudModels = cloudApiEngine.getAvailableModels() ?: emptyList()
            isFetchingCloudModels = false
        } else {
            fetchedCloudModels = emptyList()
        }
    }

    // Consent dialog state
    var showConsentDialog by remember { mutableStateOf(false) }
    var pendingModeSwitch by remember { mutableStateOf<SemanticAutomationEngine.InferenceMode?>(null) }

    // Intercept mode changes to enforce consent for Cloud API
    val handleModeChanged: (SemanticAutomationEngine.InferenceMode) -> Unit = { mode ->
        if (mode == SemanticAutomationEngine.InferenceMode.CLOUD_API && !CloudApiConsentManager.hasConsent(context)) {
            pendingModeSwitch = mode
            showConsentDialog = true
        } else {
            onInferenceModeChanged(mode)
        }
    }

    // Show consent dialog when needed
    if (showConsentDialog) {
        CloudApiDisclaimerDialog(
            onAccept = {
                CloudApiConsentManager.setConsent(context, true)
                showConsentDialog = false
                pendingModeSwitch?.let { onInferenceModeChanged(it) }
                pendingModeSwitch = null
            },
            onDecline = {
                showConsentDialog = false
                pendingModeSwitch = null
            }
        )
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            coroutineScope.launch {
                storageManager.importModelFromUri(uri)
                importedModels = storageManager.getImportedModels()
                activeModelPath = storageManager.getActiveModelPath()
                isImporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Engine Hub", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── Inference Mode Toggle ──
            item {
                InferenceModeSelector(
                    currentMode = inferenceMode,
                    onModeChanged = handleModeChanged
                )
            }

            // ── Server LLM Section ──
            if (inferenceMode == SemanticAutomationEngine.InferenceMode.SERVER_LLM) {
                item {
                ServerConnectionCard(
                    connectionStatus = connectionStatus,
                    serverUrl = serverUrl,
                    selectedModel = selectedModel,
                    availableModels = availableModels,
                    showModelDropdown = showModelDropdown,
                    onToggleDropdown = { showModelDropdown = !showModelDropdown },
                    onModelSelected = { model ->
                        localServerEngine.setModel(model)
                        showModelDropdown = false
                    },
                    manualIpInput = manualIpInput,
                    onIpChanged = { manualIpInput = it },
                    onConnect = {
                        val url = if (manualIpInput.startsWith("http")) manualIpInput
                                  else "http://$manualIpInput:11434"
                        localServerEngine.setServerUrl(url)
                        coroutineScope.launch { localServerEngine.initialize() }
                    },
                    onRefresh = {
                        coroutineScope.launch { localServerEngine.initialize() }
                    },
                    isCrossDeviceEnabled = isCrossDeviceEnabled
                )
            }
            } // End of LOCAL_SERVER_LLM section

            // ── Cloud API Section ──
            if (inferenceMode == SemanticAutomationEngine.InferenceMode.CLOUD_API) {
                item {
                    CloudApiSettingsCard(
                        cloudConnectionStatus = cloudConnectionStatus,
                        cloudErrorMessage = cloudErrorMessage,
                        selectedProvider = selectedCloudProvider,
                        apiKeyInput = apiKeyInput,
                        onApiKeyChanged = { apiKeyInput = it },
                        showApiKey = showApiKey,
                        onToggleApiKeyVisibility = { showApiKey = !showApiKey },
                        modelNameInput = modelNameInput,
                        onModelNameChanged = { modelNameInput = it },
                        customBaseUrlInput = customBaseUrlInput,
                        onBaseUrlChanged = { customBaseUrlInput = it },
                        showProviderDropdown = showProviderDropdown,
                        onToggleProviderDropdown = { showProviderDropdown = !showProviderDropdown },
                        onProviderSelected = { provider ->
                            cloudApiEngine.setProvider(provider)
                            modelNameInput = provider.defaultModel
                            customBaseUrlInput = provider.baseUrl
                            showProviderDropdown = false
                        },
                        showModelDropdown = showCloudModelDropdown,
                        onToggleModelDropdown = { showCloudModelDropdown = !showCloudModelDropdown },
                        onModelSelected = { model ->
                            modelNameInput = model
                            cloudApiEngine.setModelName(model)
                            showCloudModelDropdown = false
                        },
                        onSaveAndConnect = {
                            cloudApiEngine.setApiKey(apiKeyInput)
                            if (selectedCloudProvider.id == "custom") {
                                cloudApiEngine.setBaseUrl(customBaseUrlInput)
                            }
                            cloudApiEngine.setModelName(modelNameInput)
                            coroutineScope.launch { cloudApiEngine.initialize() }
                        },
                        fetchedCloudModels = fetchedCloudModels,
                        isFetchingCloudModels = isFetchingCloudModels
                    )
                }
            } // End of CLOUD_API section

            // ── On-Device SLM Section ──
            if (inferenceMode == SemanticAutomationEngine.InferenceMode.LOCAL_SLM) {
                // ── Hardware Assessment ──
                item {
                HardwareCard(totalRamGb)
            }

            // ── On-Device SLM Catalog ──
            item {
                Text("On-Device SLM Catalog", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Models compatible with your device are highlighted. Tap to download.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(MODEL_CATALOG) { model ->
                ModelCatalogCard(
                    model = model,
                    totalRamGb = totalRamGb,
                    context = context
                )
            }

            // ── Import Section ──
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Installed Models", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        enabled = !isImporting
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import .bin/.task")
                    }
                }

                if (isImporting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Copying binary into app storage…",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // ── Installed Models List ──
            if (importedModels.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No models imported yet. Download one above, then import here.")
                    }
                }
            } else {
                items(importedModels) { file ->
                    InstalledModelCard(
                        file = file,
                        isActive = file.absolutePath == activeModelPath,
                        onSetActive = {
                            storageManager.setActiveModelPath(file.absolutePath)
                            activeModelPath = file.absolutePath
                        },
                        onDelete = {
                            storageManager.removeModel(file)
                            importedModels = storageManager.getImportedModels()
                            activeModelPath = storageManager.getActiveModelPath()
                        }
                    )
                }
            }
            } // End of ON_DEVICE_SLM section
        }
    }
}

// ─── Sub-Composables ─────────────────────────────────────────

@Composable
private fun HardwareCard(totalRamGb: Double) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Device Hardware", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Total RAM: ${String.format("%.1f", totalRamGb)} GB", fontSize = 15.sp)

            Spacer(modifier = Modifier.height(4.dp))
            val bestModel = when {
                totalRamGb >= 12.0 -> "7B models (CPU & GPU)"
                totalRamGb >= 8.0 -> "7B models (CPU)"
                totalRamGb >= 4.0 -> "2B models"
                else -> "None recommended"
            }
            val bestColor = if (totalRamGb >= 4.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            Text("Best fit: $bestModel", color = bestColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

            if (totalRamGb < 4.0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = "Warning", tint = Color.Red, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Device has less than 4GB RAM. SLMs may cause crashes.",
                        color = Color.Red,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogCard(model: SLMModelInfo, totalRamGb: Double, context: Context) {
    val isCompatible = totalRamGb >= model.minRamGb
    val isRecommended = totalRamGb >= model.recommendedRamGb

    val containerColor = when {
        isRecommended -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        isCompatible -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = if (isRecommended) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(model.parameterCount, fontSize = 11.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text(model.quantization, fontSize = 11.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                    SuggestionChip(
                        onClick = {},
                        label = { Text(model.runtime, fontSize = 11.sp) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(model.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Size: ${String.format("%.1f", model.sizeGb)} GB", fontSize = 12.sp, color = Color.Gray)
                Text("Min RAM: ${model.minRamGb.toInt()} GB", fontSize = 12.sp, color = Color.Gray)
            }

            // Compatibility badge
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRecommended) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Recommended for your device", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else if (isCompatible) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA000), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Compatible (may be slow)", color = Color(0xFFFFA000), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Not enough RAM (${model.minRamGb.toInt()} GB required)", color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Download button
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(model.downloadUrl))
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = isCompatible
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download from ${model.source}")
            }
        }
    }
}

@Composable
private fun InstalledModelCard(
    file: File,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        border = if (isActive) null else BorderStroke(1.dp, Color.Gray),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetActive() }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${String.format("%.2f", file.length() / (1024.0 * 1024.0 * 1024.0))} GB",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                if (isActive) {
                    Text("ACTIVE", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ─── Inference Mode Selector ─────────────────────────────────

@Composable
private fun InferenceModeSelector(
    currentMode: SemanticAutomationEngine.InferenceMode,
    onModeChanged: (SemanticAutomationEngine.InferenceMode) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints {
            val isCompact = maxWidth < 360.dp
            val buttonHeight = if (isCompact) 72.dp else 80.dp

            Column(modifier = Modifier.padding(16.dp)) {
                Text("Inference Engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Choose which AI engine powers Semantic Automation",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val slmSelected = currentMode == SemanticAutomationEngine.InferenceMode.LOCAL_SLM
                    val serverSelected = currentMode == SemanticAutomationEngine.InferenceMode.SERVER_LLM
                    val cloudSelected = currentMode == SemanticAutomationEngine.InferenceMode.CLOUD_API

                    @Composable
                    fun ModeButton(
                        selected: Boolean,
                        icon: androidx.compose.ui.graphics.vector.ImageVector,
                        label: String,
                        onClick: () -> Unit
                    ) {
                        OutlinedButton(
                            onClick = onClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(buttonHeight),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    label,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = if (isCompact) 11.sp else 12.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                    }

                    ModeButton(
                        selected = slmSelected,
                        icon = Icons.Default.Memory,
                        label = if (isCompact) "On-Device" else "On-Device",
                        onClick = { onModeChanged(SemanticAutomationEngine.InferenceMode.LOCAL_SLM) }
                    )

                    ModeButton(
                        selected = serverSelected,
                        icon = Icons.Default.Wifi,
                        label = "Local Server",
                        onClick = { onModeChanged(SemanticAutomationEngine.InferenceMode.SERVER_LLM) }
                    )

                    ModeButton(
                        selected = cloudSelected,
                        icon = Icons.Default.Cloud,
                        label = "Cloud API",
                        onClick = { onModeChanged(SemanticAutomationEngine.InferenceMode.CLOUD_API) }
                    )
                }
            }
        }
    }
}

// ─── Server Connection Card ──────────────────────────────────

@Composable
private fun ServerConnectionCard(
    connectionStatus: ServerConnectionStatus,
    serverUrl: String,
    selectedModel: String,
    availableModels: List<String>,
    showModelDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    onModelSelected: (String) -> Unit,
    manualIpInput: String,
    onIpChanged: (String) -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    isCrossDeviceEnabled: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (connectionStatus) {
                ServerConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ServerConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                ServerConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header with status indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusIcon = when (connectionStatus) {
                        ServerConnectionStatus.CONNECTED -> Icons.Default.Cloud
                        ServerConnectionStatus.CONNECTING -> Icons.Default.Wifi
                        ServerConnectionStatus.DISCONNECTED -> Icons.Default.CloudOff
                    }
                    val statusColor = when (connectionStatus) {
                        ServerConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                        ServerConnectionStatus.CONNECTING -> Color(0xFFFFA000)
                        ServerConnectionStatus.DISCONNECTED -> Color.Gray
                    }

                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Local Server LLM", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                // Status chip
                val statusText = when (connectionStatus) {
                    ServerConnectionStatus.CONNECTED -> "Connected"
                    ServerConnectionStatus.CONNECTING -> "Connecting…"
                    ServerConnectionStatus.DISCONNECTED -> "Not Connected"
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text(
                        statusText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = when (connectionStatus) {
                            ServerConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            ServerConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                            ServerConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Connected info
            if (connectionStatus == ServerConnectionStatus.CONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Server: $serverUrl", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selectedModel.isNotBlank()) {
                    Text("Active Model: $selectedModel", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Connecting spinner
            if (connectionStatus == ServerConnectionStatus.CONNECTING) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Connecting to Ollama server…", fontSize = 13.sp)
                }
            }

            // Model Selector Dropdown
            if (connectionStatus == ServerConnectionStatus.CONNECTED && availableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Available Models:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))

                Box {
                    OutlinedButton(
                        onClick = onToggleDropdown,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selectedModel.isNotBlank()) selectedModel else "Select a model…",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = onToggleDropdown
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (model == selectedModel) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF4CAF50),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(model)
                                    }
                                },
                                onClick = { onModelSelected(model) }
                            )
                        }
                    }
                }

                // Refresh button
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Refresh models", fontSize = 12.sp)
                }
            }

            // Manual IP Input (always shown, useful when disconnected)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Server Address:", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualIpInput,
                    onValueChange = onIpChanged,
                    placeholder = { Text("e.g. 192.168.1.5", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )
                Button(
                    onClick = onConnect,
                    enabled = manualIpInput.isNotBlank()
                ) {
                    Text("Connect")
                }
            }

            // Cross-Device Auto-Discovery Reminder
            if (!isCrossDeviceEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "💡 Enable Cross-Device Automation for auto-discovery of desktop servers on your WiFi network.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

// ─── Cloud API Settings Card ─────────────────────────────────

@Composable
private fun CloudApiSettingsCard(
    cloudConnectionStatus: CloudApiConnectionStatus,
    cloudErrorMessage: String?,
    selectedProvider: com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiProvider,
    apiKeyInput: String,
    onApiKeyChanged: (String) -> Unit,
    showApiKey: Boolean,
    onToggleApiKeyVisibility: () -> Unit,
    modelNameInput: String,
    onModelNameChanged: (String) -> Unit,
    customBaseUrlInput: String,
    onBaseUrlChanged: (String) -> Unit,
    showProviderDropdown: Boolean,
    onToggleProviderDropdown: () -> Unit,
    onProviderSelected: (com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiProvider) -> Unit,
    showModelDropdown: Boolean,
    onToggleModelDropdown: () -> Unit,
    onModelSelected: (String) -> Unit,
    onSaveAndConnect: () -> Unit,
    fetchedCloudModels: List<String> = emptyList(),
    isFetchingCloudModels: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (cloudConnectionStatus) {
                CloudApiConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                CloudApiConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                CloudApiConnectionStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                CloudApiConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (cloudConnectionStatus) {
                            CloudApiConnectionStatus.CONNECTED -> Icons.Default.CheckCircle
                            CloudApiConnectionStatus.ERROR -> Icons.Default.Warning
                            else -> Icons.Default.Cloud
                        },
                        contentDescription = null,
                        tint = when (cloudConnectionStatus) {
                            CloudApiConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            CloudApiConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Cloud API Configuration",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Text(
                        text = when (cloudConnectionStatus) {
                            CloudApiConnectionStatus.CONNECTED -> "Connected"
                            CloudApiConnectionStatus.CONNECTING -> "Testing…"
                            CloudApiConnectionStatus.ERROR -> "Error"
                            CloudApiConnectionStatus.DISCONNECTED -> "Not Connected"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = when (cloudConnectionStatus) {
                            CloudApiConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            CloudApiConnectionStatus.ERROR -> MaterialTheme.colorScheme.error
                            CloudApiConnectionStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (cloudConnectionStatus == CloudApiConnectionStatus.CONNECTING) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // ── Error message ──
            if (!cloudErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        cloudErrorMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Provider Selector ──
            Text("Provider", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onToggleProviderDropdown,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        selectedProvider.displayName,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Start
                    )
                    Text("▼", fontSize = 12.sp)
                }
                DropdownMenu(
                    expanded = showProviderDropdown,
                    onDismissRequest = onToggleProviderDropdown
                ) {
                    CLOUD_API_PROVIDERS.forEach { provider ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(provider.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        provider.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onClick = { onProviderSelected(provider) },
                            leadingIcon = {
                                if (provider.id == selectedProvider.id) {
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── API Key ──
            Text("API Key", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("sk-...  or  AIza...", fontSize = 14.sp) },
                singleLine = true,
                visualTransformation = if (showApiKey) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = onToggleApiKeyVisibility) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide" else "Show",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )
            Text(
                "\uD83D\uDD12 Encrypted with AES-256-GCM via Android Keystore",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Model Name ──
            Text("Model", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(6.dp))
            if (isFetchingCloudModels) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetching models from cloud...", fontSize = 13.sp)
                }
            } else if (fetchedCloudModels.isNotEmpty() || selectedProvider.suggestedModels.isNotEmpty()) {
                val modelsToShow = if (fetchedCloudModels.isNotEmpty()) fetchedCloudModels else selectedProvider.suggestedModels
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onToggleModelDropdown,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            modelNameInput.ifBlank { "Select model" },
                            modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            fontSize = 13.sp
                        )
                        Text("▼", fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = onToggleModelDropdown
                    ) {
                        modelsToShow.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model, fontSize = 13.sp) },
                                onClick = { onModelSelected(model) },
                                leadingIcon = {
                                    if (model == modelNameInput) {
                                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = modelNameInput,
                    onValueChange = onModelNameChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. gpt-4o-mini", fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )
            }

            // ── Custom Base URL (only for Custom provider) ──
            if (selectedProvider.id == "custom") {
                Spacer(modifier = Modifier.height(12.dp))
                Text("API Endpoint URL", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = customBaseUrlInput,
                    onValueChange = onBaseUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your-api.example.com/v1/chat/completions", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Connect Button ──
            Button(
                onClick = onSaveAndConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKeyInput.isNotBlank() && modelNameInput.isNotBlank() &&
                        (selectedProvider.id != "custom" || customBaseUrlInput.isNotBlank()) &&
                        cloudConnectionStatus != CloudApiConnectionStatus.CONNECTING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    if (cloudConnectionStatus == CloudApiConnectionStatus.CONNECTED) Icons.Default.Refresh else Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (cloudConnectionStatus == CloudApiConnectionStatus.CONNECTED) "Re-test Connection" else "Save & Test Connection",
                    fontWeight = FontWeight.Bold
                )
            }

            // ── Connection info ──
            if (cloudConnectionStatus == CloudApiConnectionStatus.CONNECTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "✅ Connected to ${selectedProvider.displayName} — ${modelNameInput}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
