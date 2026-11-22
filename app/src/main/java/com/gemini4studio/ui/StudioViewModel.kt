package com.gemini4studio.ui

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemini4studio.data.*
import com.gemini4studio.network.GeminiApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StudioUiState(
    val apiKey: String = "",
    val showApiKeyDialog: Boolean = false,
    val isLoadingModels: Boolean = false,
    val detectedModels: List<DetectedModel> = emptyList(),
    val activeFeature: StudioFeature = StudioFeature.CHAT_AND_IDE,
    val selectedModelByFeature: Map<StudioFeature, String> = mapOf(
        StudioFeature.CHAT_AND_IDE to "gemini-4-hydrogen",
        StudioFeature.IMAGE_GEN to "gemini-nano-banana-2.1",
        StudioFeature.VIDEO_GEN to "gemini-omni-1.2-flash",
        StudioFeature.MUSIC_GEN to "lyria-3.5"
    ),
    val messages: List<ChatMessage> = emptyList(),
    val pendingAttachments: List<AttachedFile> = emptyList(),
    val isBusy: Boolean = false,
    val statusBanner: String? = null,
    val errorMessage: String? = null,
    val gemini4Config: Gemini4Config = Gemini4Config(),
    val imageConfig: ImageGenConfig = ImageGenConfig(),
    val videoConfig: VideoGenConfig = VideoGenConfig(),
    val showIdePane: Boolean = false,
    val includeIdeWorkspaceInPrompt: Boolean = true,
    val ideFiles: List<IdeFile> = listOf(
        IdeFile(
            name = "index.html",
            language = "html",
            content = """
                <!DOCTYPE html>
                <html>
                <head>
                  <style>
                    body { font-family: sans-serif; background: #0f172a; color: #f8fafc; padding: 24px; }
                    button { background: #38bdf8; border: none; padding: 10px 18px; border-radius: 8px; font-weight: bold; }
                  </style>
                </head>
                <body>
                  <h2>Gemini 4 Live IDE</h2>
                  <p>Ask Gemini 4 to build or edit any file here, then tap Web Preview!</p>
                  <button onclick="document.querySelector('p').innerText='Running live!'">Test Script</button>
                </body>
                </html>
            """.trimIndent()
        ),
        IdeFile(
            name = "Main.kt",
            language = "kotlin",
            content = "fun main() {\n    println(\"Ready for Gemini 4 coding!\")\n}\n"
        )
    ),
    val activeIdeFileIndex: Int = 0
) {
    val activeModelId: String
        get() = selectedModelByFeature[activeFeature] ?: "gemini-4-hydrogen"
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val keyStore = ApiKeyStore(application)
    private val api = GeminiApiClient(application)

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    init {
        val savedKey = keyStore.loadApiKey()
        _uiState.update { it.copy(apiKey = savedKey, showApiKeyDialog = savedKey.isBlank()) }
        if (savedKey.isNotBlank()) {
            refreshModels(savedKey)
        }
    }

    fun saveApiKey(newKey: String) {
        val clean = newKey.trim()
        keyStore.saveApiKey(clean)
        _uiState.update { it.copy(apiKey = clean, showApiKeyDialog = false, errorMessage = null) }
        if (clean.isNotBlank()) {
            refreshModels(clean)
        }
    }

    fun setApiKeyDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showApiKeyDialog = visible) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun refreshModels(keyOverride: String? = null) {
        val key = keyOverride ?: _uiState.value.apiKey
        if (key.isBlank()) {
            _uiState.update { it.copy(showApiKeyDialog = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModels = true, errorMessage = null) }
            runCatching { api.listModels(key) }
                .onSuccess { models ->
                    val updatedSelection = _uiState.value.selectedModelByFeature.toMutableMap()
                    StudioFeature.entries.forEach { feature ->
                        val candidates = models.filter { it.category == feature }
                        val current = updatedSelection[feature]
                        if (candidates.isNotEmpty() && candidates.none { it.id == current }) {
                            updatedSelection[feature] = candidates.first().id
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingModels = false,
                            detectedModels = models,
                            selectedModelByFeature = updatedSelection,
                            statusBanner = "Detected ${models.size} models from your API key"
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoadingModels = false, errorMessage = e.message ?: "Failed to load models")
                    }
                }
        }
    }

    fun selectFeature(feature: StudioFeature) = _uiState.update { it.copy(activeFeature = feature) }

    fun selectModel(modelId: String) {
        val feature = _uiState.value.activeFeature
        _uiState.update {
            it.copy(selectedModelByFeature = it.selectedModelByFeature + (feature to modelId))
        }
    }

    fun updateGemini4Config(transform: (Gemini4Config) -> Gemini4Config) {
        _uiState.update { it.copy(gemini4Config = transform(it.gemini4Config)) }
    }

    fun updateImageConfig(transform: (ImageGenConfig) -> ImageGenConfig) {
        _uiState.update { it.copy(imageConfig = transform(it.imageConfig)) }
    }

    fun updateVideoConfig(transform: (VideoGenConfig) -> VideoGenConfig) {
        _uiState.update { it.copy(videoConfig = transform(it.videoConfig)) }
    }

    fun toggleIdePane() = _uiState.update { it.copy(showIdePane = !it.showIdePane) }

    fun toggleIncludeWorkspace() = _uiState.update {
        it.copy(includeIdeWorkspaceInPrompt = !it.includeIdeWorkspaceInPrompt)
    }

    fun selectIdeFile(index: Int) = _uiState.update {
        it.copy(activeIdeFileIndex = index.coerceIn(0, (it.ideFiles.size - 1).coerceAtLeast(0)))
    }

    fun updateActiveIdeContent(newContent: String) {
        _uiState.update { state ->
            val files = state.ideFiles.toMutableList()
            if (state.activeIdeFileIndex in files.indices) {
                files[state.activeIdeFileIndex] = files[state.activeIdeFileIndex].copy(content = newContent)
            }
            state.copy(ideFiles = files)
        }
    }

    fun addOrOpenCodeInIde(name: String, language: String, content: String) {
        _uiState.update { state ->
            val files = state.ideFiles.toMutableList()
            val existingIdx = files.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            val targetIdx = if (existingIdx >= 0) {
                files[existingIdx] = files[existingIdx].copy(content = content, language = language)
                existingIdx
            } else {
                files.add(IdeFile(name = name, language = language, content = content))
                files.lastIndex
            }
            state.copy(ideFiles = files, activeIdeFileIndex = targetIdx, showIdePane = true)
        }
    }

    fun deleteIdeFile(index: Int) {
        _uiState.update { state ->
            if (state.ideFiles.size <= 1) return@update state
            val files = state.ideFiles.toMutableList().apply { removeAt(index) }
            state.copy(
                ideFiles = files,
                activeIdeFileIndex = state.activeIdeFileIndex.coerceAtMost(files.lastIndex)
            )
        }
    }

    fun addAttachments(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val added = uris.map { uri ->
            var name = "file_${System.currentTimeMillis()}"
            var size = 0L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            AttachedFile(uri = uri, displayName = name, mimeType = mime, sizeBytes = size)
        }
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + added) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
    }

    fun stopGeneration() {
        activeJob?.cancel()
        _uiState.update { it.copy(isBusy = false, statusBanner = "Generation stopped") }
    }

    fun clearChat() {
        _uiState.update {
            it.copy(
                messages = emptyList(),
                videoConfig = it.videoConfig.copy(previousInteractionId = null)
            )
        }
    }

    fun sendPrompt(prompt: String) {
        val state = _uiState.value
        if (state.apiKey.isBlank()) {
            _uiState.update { it.copy(showApiKeyDialog = true) }
            return
        }
        if (prompt.isBlank() && state.pendingAttachments.isEmpty()) return

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null, statusBanner = "Preparing files...") }

            try {
                val preparedFiles = state.pendingAttachments.map { att ->
                    _uiState.update { it.copy(statusBanner = "Uploading ${att.displayName}...") }
                    api.prepareAttachment(state.apiKey, att)
                }

                preparedFiles.filter { it.extractedText != null }.forEach { f ->
                    val ext = f.displayName.substringAfterLast('.', "txt").lowercase()
                    addOrOpenCodeInIde(f.displayName, ext, f.extractedText!!)
                }

                val userMsg = ChatMessage(isUser = true, text = prompt, attachments = preparedFiles)
                val assistantMsgId = java.util.UUID.randomUUID().toString()
                val placeholder = ChatMessage(
                    id = assistantMsgId,
                    isUser = false,
                    modelUsed = state.activeModelId,
                    isStreaming = true
                )

                _uiState.update {
                    it.copy(
                        messages = it.messages + userMsg + placeholder,
                        pendingAttachments = emptyList(),
                        statusBanner = "Running ${state.activeModelId}..."
                    )
                }

                when (state.activeFeature) {
                    StudioFeature.CHAT_AND_IDE -> runGemini4Chat(assistantMsgId)
                    StudioFeature.IMAGE_GEN -> runImageGeneration(assistantMsgId, prompt, preparedFiles)
                    StudioFeature.VIDEO_GEN -> runVideoGeneration(assistantMsgId, prompt, preparedFiles)
                    StudioFeature.MUSIC_GEN -> runMusicGeneration(assistantMsgId, prompt, preparedFiles)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = null,
                        errorMessage = e.message ?: "Request failed"
                    )
                }
            }
        }
    }

    private suspend fun runGemini4Chat(assistantMsgId: String) {
        val state = _uiState.value
        val historyForApi = state.messages.filterNot { it.id == assistantMsgId }

        api.streamChat(
            apiKey = state.apiKey,
            modelId = state.activeModelId,
            history = historyForApi,
            ideWorkspaceFiles = state.ideFiles,
            includeWorkspace = state.includeIdeWorkspaceInPrompt,
            config = state.gemini4Config
        ).collect { event ->
            updateMessage(assistantMsgId) { msg ->
                val updatedExecs = if (event.codeBlock != null) msg.codeExecutions + event.codeBlock else msg.codeExecutions
                val updatedMedia = if (event.generatedImage != null) msg.generatedMedia + event.generatedImage else msg.generatedMedia
                msg.copy(
                    text = msg.text + event.textDelta,
                    thinkingText = msg.thinkingText + event.thoughtDelta,
                    codeExecutions = updatedExecs,
                    generatedMedia = updatedMedia,
                    tokenUsage = event.usage ?: msg.tokenUsage
                )
            }
        }

        updateMessage(assistantMsgId) { it.copy(isStreaming = false) }
        _uiState.update { it.copy(isBusy = false, statusBanner = null) }
    }

    private suspend fun runImageGeneration(assistantMsgId: String, prompt: String, files: List<AttachedFile>) {
        val state = _uiState.value
        val (caption, images) = api.generateImage(
            apiKey = state.apiKey,
            modelId = state.activeModelId,
            prompt = prompt,
            referenceFiles = files,
            imageConfig = state.imageConfig
        )
        updateMessage(assistantMsgId) {
            it.copy(
                text = caption.ifBlank { "Generated ${images.size} image(s) with ${state.activeModelId}." },
                generatedMedia = images,
                isStreaming = false
            )
        }
        _uiState.update { it.copy(isBusy = false, statusBanner = null) }
    }

    private suspend fun runVideoGeneration(assistantMsgId: String, prompt: String, files: List<AttachedFile>) {
        val state = _uiState.value
        val video = api.generateVideo(
            apiKey = state.apiKey,
            modelId = state.activeModelId,
            prompt = prompt,
            referenceFiles = files,
            videoConfig = state.videoConfig
        )
        _uiState.update {
            it.copy(videoConfig = it.videoConfig.copy(previousInteractionId = video.interactionId))
        }
        updateMessage(assistantMsgId) {
            it.copy(
                text = video.captionOrLyrics ?: "Video generated with native audio (${state.activeModelId}).",
                generatedMedia = listOf(video),
                isStreaming = false
            )
        }
        _uiState.update { it.copy(isBusy = false, statusBanner = null) }
    }

    private suspend fun runMusicGeneration(assistantMsgId: String, prompt: String, files: List<AttachedFile>) {
        val state = _uiState.value
        val track = api.generateMusic(
            apiKey = state.apiKey,
            modelId = state.activeModelId,
            prompt = prompt,
            referenceFiles = files
        )
        updateMessage(assistantMsgId) {
            it.copy(
                text = track.captionOrLyrics ?: "Music track generated with ${state.activeModelId}.",
                generatedMedia = listOf(track),
                isStreaming = false
            )
        }
        _uiState.update { it.copy(isBusy = false, statusBanner = null) }
    }

    private fun updateMessage(id: String, transform: (ChatMessage) -> ChatMessage) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    fun saveMediaToDevice(media: GeneratedMedia) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val collection = when (media.type) {
                    MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, media.file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, media.mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(collection, values)
                    ?: throw IllegalStateException("Could not create MediaStore entry")
                resolver.openOutputStream(uri)?.use { out ->
                    media.file.inputStream().use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusBanner = "Saved ${media.file.name} to device!") }
                }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(errorMessage = "Save failed: ${e.message}") }
                }
            }
        }
    }
}
