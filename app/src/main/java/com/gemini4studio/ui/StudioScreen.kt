package com.gemini4studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemini4studio.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(viewModel: StudioViewModel) {
    val state by viewModel.uiState.collectAsState()
    var promptText by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addAttachments(uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Gemini 4 Studio", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = state.activeModelId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    if (state.activeFeature == StudioFeature.CHAT_AND_IDE) {
                        IconButton(onClick = { viewModel.toggleIdePane() }) {
                            Icon(
                                Icons.Default.Code,
                                contentDescription = "Toggle IDE",
                                tint = if (state.showIdePane) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Select Model")
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Feature Settings")
                    }
                    IconButton(onClick = { viewModel.setApiKeyDialogVisible(true) }) {
                        Icon(Icons.Default.Key, contentDescription = "API Key")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StudioFeature.entries.forEach { feature ->
                    FilterChip(
                        selected = state.activeFeature == feature,
                        onClick = { viewModel.selectFeature(feature) },
                        label = { Text(feature.label) }
                    )
                }
                AssistChip(
                    onClick = { showModelPicker = true },
                    label = {
                        Text(
                            if (state.isLoadingModels) "Detecting..."
                            else "${state.detectedModels.size} Models"
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            state.statusBanner?.let { banner ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = banner,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            state.errorMessage?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = err,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                if (state.showIdePane && state.activeFeature == StudioFeature.CHAT_AND_IDE) {
                    IdeWorkspacePane(
                        files = state.ideFiles,
                        activeIndex = state.activeIdeFileIndex,
                        includeInPrompt = state.includeIdeWorkspaceInPrompt,
                        onSelectFile = viewModel::selectIdeFile,
                        onUpdateContent = viewModel::updateActiveIdeContent,
                        onAddFile = { name, lang -> viewModel.addOrOpenCodeInIde(name, lang, "") },
                        onDeleteFile = viewModel::deleteIdeFile,
                        onToggleIncludeInPrompt = viewModel::toggleIncludeWorkspace,
                        modifier = Modifier.weight(0.55f)
                    )
                    HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
                }

                val listState = rememberLazyListState()
                LaunchedEffect(state.messages.size) {
                    if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(0.45f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(
                            message = msg,
                            onOpenCodeInIde = { name, lang, code ->
                                viewModel.addOrOpenCodeInIde(name, lang, code)
                            },
                            onSaveMedia = viewModel::saveMediaToDevice
                        )
                    }
                }
            }

            if (state.pendingAttachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.pendingAttachments.forEach { att ->
                        InputChip(
                            selected = true,
                            onClick = { viewModel.removeAttachment(att.id) },
                            label = { Text(att.displayName, maxLines = 1) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach any file")
                    }
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        placeholder = {
                            Text(
                                when (state.activeFeature) {
                                    StudioFeature.CHAT_AND_IDE -> "Ask Gemini 4 to code, debug, or analyze files..."
                                    StudioFeature.IMAGE_GEN -> "Describe an image (or attach references to edit)..."
                                    StudioFeature.VIDEO_GEN -> "Describe a 3–15s video with audio (or edit last clip)..."
                                    StudioFeature.MUSIC_GEN -> "Describe song genre, instruments, mood, or lyrics..."
                                }
                            )
                        }
                    )
                    Spacer(Modifier.width(6.dp))
                    if (state.isBusy) {
                        IconButton(onClick = { viewModel.stopGeneration() }) {
                            Icon(Icons.Default.StopCircle, contentDescription = "Stop", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        FilledIconButton(onClick = {
                            val toSend = promptText
                            promptText = ""
                            viewModel.sendPrompt(toSend)
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }

    if (state.showApiKeyDialog) {
        var keyInput by remember(state.apiKey) { mutableStateOf(state.apiKey) }
        AlertDialog(
            onDismissRequest = { if (state.apiKey.isNotBlank()) viewModel.setApiKeyDialogVisible(false) },
            title = { Text("Google AI Studio API Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter your Google AI Studio API key. It is encrypted with Android Keystore on your device and used directly to detect models and call the Gemini API.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("API Key (AIza...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.saveApiKey(keyInput) }) {
                    Text("Save & Detect Models")
                }
            },
            dismissButton = {
                if (state.apiKey.isNotBlank()) {
                    TextButton(onClick = { viewModel.setApiKeyDialogVisible(false) }) { Text("Cancel") }
                }
            }
        )
    }

    if (showModelPicker) {
        ModalBottomSheet(onDismissRequest = { showModelPicker = false }) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Detected API Models (${state.activeFeature.label})", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { viewModel.refreshModels() }) { Text("Refresh") }
                }
                Spacer(Modifier.height(8.dp))

                val matching = state.detectedModels.filter { it.category == state.activeFeature }
                val others = state.detectedModels.filter { it.category != state.activeFeature }

                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (matching.isNotEmpty()) {
                        item {
                            Text("Recommended for ${state.activeFeature.label}", style = MaterialTheme.typography.labelLarge)
                        }
                        items(matching) { m ->
                            ListItem(
                                headlineContent = { Text(m.displayName) },
                                supportingContent = { Text("${m.id} • In: ${m.inputTokenLimit} / Out: ${m.outputTokenLimit}") },
                                trailingContent = {
                                    RadioButton(
                                        selected = state.activeModelId == m.id,
                                        onClick = { viewModel.selectModel(m.id); showModelPicker = false }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectModel(m.id); showModelPicker = false }
                            )
                        }
                    }
                    if (others.isNotEmpty()) {
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("All Other Detected Models", style = MaterialTheme.typography.labelLarge)
                        }
                        items(others) { m ->
                            ListItem(
                                headlineContent = { Text(m.displayName) },
                                supportingContent = { Text(m.id) },
                                trailingContent = {
                                    RadioButton(
                                        selected = state.activeModelId == m.id,
                                        onClick = { viewModel.selectModel(m.id); showModelPicker = false }
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectModel(m.id); showModelPicker = false }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(onDismissRequest = { showSettingsSheet = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Gemini 4 & Studio Controls", style = MaterialTheme.typography.titleMedium)

                when (state.activeFeature) {
                    StudioFeature.CHAT_AND_IDE -> {
                        Text("Thinking Level", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ThinkingLevel.entries.forEach { lvl ->
                                FilterChip(
                                    selected = state.gemini4Config.thinkingLevel == lvl,
                                    onClick = { viewModel.updateGemini4Config { it.copy(thinkingLevel = lvl) } },
                                    label = { Text(lvl.label) }
                                )
                            }
                        }
                        SettingSwitch("Code Execution Sandbox", state.gemini4Config.enableCodeExecution) { v ->
                            viewModel.updateGemini4Config { it.copy(enableCodeExecution = v) }
                        }
                        SettingSwitch("Google Search Grounding", state.gemini4Config.enableGoogleSearch) { v ->
                            viewModel.updateGemini4Config { it.copy(enableGoogleSearch = v) }
                        }
                        SettingSwitch("Long Decode (>64K auto-continue)", state.gemini4Config.enableLongDecode) { v ->
                            viewModel.updateGemini4Config {
                                it.copy(enableLongDecode = v, maxOutputTokens = if (v) 262144 else 65536)
                            }
                        }
                        SettingSwitch("Structured JSON Output", state.gemini4Config.enableJsonMode) { v ->
                            viewModel.updateGemini4Config { it.copy(enableJsonMode = v) }
                        }
                    }
                    StudioFeature.IMAGE_GEN -> {
                        Text("Aspect Ratio", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("1:1", "16:9", "9:16", "4:3").forEach { ar ->
                                FilterChip(
                                    selected = state.imageConfig.aspectRatio == ar,
                                    onClick = { viewModel.updateImageConfig { it.copy(aspectRatio = ar) } },
                                    label = { Text(ar) }
                                )
                            }
                        }
                        Text("Resolution", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("1K", "2K", "4K").forEach { res ->
                                FilterChip(
                                    selected = state.imageConfig.resolution == res,
                                    onClick = { viewModel.updateImageConfig { it.copy(resolution = res) } },
                                    label = { Text(res) }
                                )
                            }
                        }
                    }
                    StudioFeature.VIDEO_GEN -> {
                        Text("Duration: ${state.videoConfig.durationSeconds}s (3–15s)", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = state.videoConfig.durationSeconds.toFloat(),
                            onValueChange = { s -> viewModel.updateVideoConfig { it.copy(durationSeconds = s.toInt()) } },
                            valueRange = 3f..15f,
                            steps = 11
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("16:9", "9:16").forEach { ar ->
                                FilterChip(
                                    selected = state.videoConfig.aspectRatio == ar,
                                    onClick = { viewModel.updateVideoConfig { it.copy(aspectRatio = ar) } },
                                    label = { Text(ar) }
                                )
                            }
                            listOf("720p", "1080p").forEach { res ->
                                FilterChip(
                                    selected = state.videoConfig.resolution == res,
                                    onClick = { viewModel.updateVideoConfig { it.copy(resolution = res) } },
                                    label = { Text(res) }
                                )
                            }
                        }
                        if (state.videoConfig.previousInteractionId != null) {
                            OutlinedButton(onClick = {
                                viewModel.updateVideoConfig { it.copy(previousInteractionId = null) }
                            }) {
                                Text("Start New Video (Clear Edit Chain)")
                            }
                        }
                    }
                    StudioFeature.MUSIC_GEN -> {
                        Text(
                            "Tip: Include genre, BPM, instruments, mood, and sections ([Intro], [Verse], [Chorus]) or attach an image to inspire Lyria 3.5.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                HorizontalDivider()
                TextButton(onClick = { viewModel.clearChat(); showSettingsSheet = false }) {
                    Text("Clear Conversation", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    onOpenCodeInIde: (name: String, lang: String, code: String) -> Unit,
    onSaveMedia: (GeneratedMedia) -> Unit
) {
    val bg = if (message.isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    var showThoughts by remember { mutableStateOf(false) }

    val codeBlocks = remember(message.text) {
        Regex("```(\\w*)\\n([\\s\\S]*?)```").findAll(message.text).mapIndexed { idx, match ->
            val lang = match.groupValues[1].ifBlank { "txt" }
            val ext = when (lang.lowercase()) {
                "kotlin", "kt" -> "kt"
                "python", "py" -> "py"
                "javascript", "js" -> "js"
                "typescript", "ts" -> "ts"
                "html" -> "html"
                "css" -> "css"
                else -> lang
            }
            Triple("snippet_${idx + 1}.$ext", lang, match.groupValues[2].trim())
        }.toList()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            if (!message.isUser && message.modelUsed != null) {
                Text(
                    text = message.modelUsed,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (message.thinkingText.isNotBlank()) {
                TextButton(
                    onClick = { showThoughts = !showThoughts },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(if (showThoughts) "Hide Gemini 4 Reasoning" else "Show Gemini 4 Reasoning")
                }
                if (showThoughts) {
                    Text(
                        text = message.thinkingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (message.text.isNotBlank()) {
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }

            if (!message.isUser && codeBlocks.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    codeBlocks.forEach { (fname, lang, code) ->
                        AssistChip(
                            onClick = { onOpenCodeInIde(fname, lang, code) },
                            label = { Text("Open $fname in IDE", fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            message.codeExecutions.forEach { exec ->
                Surface(
                    color = Color(0xFF0D1117),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            text = if (exec.language == "RESULT") "Execution Output (${exec.outcome ?: "OK"})"
                            else "Executed ${exec.language}",
                            color = Color(0xFF58A6FF),
                            fontSize = 11.sp
                        )
                        Text(
                            text = exec.output ?: exec.code,
                            color = Color(0xFFE6EDF3),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            message.generatedMedia.forEach { media ->
                GeneratedMediaCard(media = media, onSave = onSaveMedia)
            }

            message.tokenUsage?.let { u ->
                Text(
                    text = "Tokens • In: ${u.promptTokens} | Think: ${u.thoughtsTokens} | Out: ${u.outputTokens} | Cached: ${u.cachedTokens}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}
