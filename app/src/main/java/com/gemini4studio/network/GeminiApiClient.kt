package com.gemini4studio.network

import android.content.Context
import android.util.Base64
import com.gemini4studio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class StreamChunkEvent(
    val textDelta: String = "",
    val thoughtDelta: String = "",
    val codeBlock: CodeExecutionBlock? = null,
    val generatedImage: GeneratedMedia? = null,
    val usage: TokenUsage? = null
)

class GeminiApiClient(private val context: Context) {

    private val baseUrl = "https://generativelanguage.googleapis.com"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun listModels(apiKey: String): List<DetectedModel> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<DetectedModel>()
        var pageToken: String? = null

        do {
            val url = buildString {
                append("$baseUrl/v1beta/models?pageSize=100")
                if (!pageToken.isNullOrBlank()) append("&pageToken=$pageToken")
            }
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Model detection failed (${resp.code}): $body")
                }
                val root = json.parseToJsonElement(body).jsonObject
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull

                root["models"]?.jsonArray?.forEach { elem ->
                    val obj = elem.jsonObject
                    val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val id = rawName.removePrefix("models/")
                    val displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: id
                    val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val inLimit = obj["inputTokenLimit"]?.jsonPrimitive?.intOrNull ?: 0
                    val outLimit = obj["outputTokenLimit"]?.jsonPrimitive?.intOrNull ?: 0
                    val methods = obj["supportedGenerationMethods"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()

                    val lower = id.lowercase()
                    if (lower.contains("embedding") || lower.contains("aqa")) return@forEach

                    val category = when {
                        lower.contains("lyria") -> StudioFeature.MUSIC_GEN
                        lower.contains("omni") || lower.contains("veo") -> StudioFeature.VIDEO_GEN
                        lower.contains("nano-banana") || lower.contains("image") || lower.contains("imagen") ->
                            StudioFeature.IMAGE_GEN
                        else -> StudioFeature.CHAT_AND_IDE
                    }

                    discovered += DetectedModel(
                        id = id,
                        displayName = displayName,
                        description = description,
                        inputTokenLimit = inLimit,
                        outputTokenLimit = outLimit,
                        supportedMethods = methods,
                        category = category
                    )
                }
            }
        } while (!pageToken.isNullOrBlank())

        discovered.sortedWith(
            compareByDescending<DetectedModel> { it.id.startsWith("gemini-4") }
                .thenByDescending { it.id.contains("nano-banana-2.1") }
                .thenByDescending { it.id.contains("omni-1.2") }
                .thenByDescending { it.id.contains("lyria-3.5") }
                .thenBy { it.id }
        )
    }

    suspend fun prepareAttachment(apiKey: String, file: AttachedFile): AttachedFile =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot read ${file.displayName}")

            val ext = file.displayName.substringAfterLast('.', "").lowercase()
            val textExtensions = setOf(
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "cpp", "h", "hpp",
                "cs", "go", "rs", "rb", "php", "swift", "dart", "html", "htm", "css", "scss",
                "xml", "json", "yaml", "yml", "toml", "md", "txt", "csv", "sql", "sh", "gradle",
                "properties", "env", "dockerfile", "makefile"
            )

            if (file.mimeType.startsWith("text/") || ext in textExtensions) {
                return@withContext file.copy(extractedText = String(bytes, Charsets.UTF_8))
            }

            val resolvedMime = normalizeMimeType(file.mimeType, ext)
            val uploadedUri = uploadToFilesApi(apiKey, file.displayName, resolvedMime, bytes)
            waitForFileActive(apiKey, uploadedUri)

            file.copy(mimeType = resolvedMime, uploadedFileUri = uploadedUri)
        }

    private fun normalizeMimeType(mime: String, ext: String): String {
        if (mime.isNotBlank() && mime != "application/octet-stream") return mime
        return when (ext) {
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun uploadToFilesApi(
        apiKey: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ): String {
        val metaJson = buildJsonObject {
            putJsonObject("file") { put("display_name", displayName) }
        }.toString()

        val startReq = Request.Builder()
            .url("$baseUrl/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metaJson.toRequestBody(jsonMediaType))
            .build()

        val uploadUrl = client.newCall(startReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("File upload init failed (${resp.code}): ${resp.body?.string()}")
            }
            resp.header("X-Goog-Upload-URL")
                ?: throw IllegalStateException("Missing X-Goog-Upload-URL header")
        }

        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        client.newCall(uploadReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("File upload finalize failed (${resp.code}): $body")
            }
            val root = json.parseToJsonElement(body).jsonObject
            return root["file"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("Missing file URI in upload response")
        }
    }

    private suspend fun waitForFileActive(apiKey: String, fileUri: String) {
        repeat(30) {
            val req = Request.Builder()
                .url(fileUri)
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val root = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                when (root["state"]?.jsonPrimitive?.contentOrNull) {
                    "ACTIVE" -> return
                    "FAILED" -> throw IllegalStateException("Gemini File API failed to process file.")
                }
            }
            delay(1500)
        }
    }

    fun streamChat(
        apiKey: String,
        modelId: String,
        history: List<ChatMessage>,
        ideWorkspaceFiles: List<IdeFile>,
        includeWorkspace: Boolean,
        config: Gemini4Config
    ): Flow<StreamChunkEvent> = flow {
        var continuationToken: String? = null
        var segmentsRemaining = if (config.enableLongDecode) 8 else 1

        do {
            val payload = buildGenerateContentPayload(
                modelId = modelId,
                history = history,
                ideWorkspaceFiles = ideWorkspaceFiles,
                includeWorkspace = includeWorkspace,
                config = config,
                continuationToken = continuationToken
            )

            val request = Request.Builder()
                .url("$baseUrl/v1beta/models/$modelId:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            var nextToken: String? = null

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    throw IllegalStateException("API Error ${response.code}: $err")
                }

                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line!!.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val dataStr = trimmed.removePrefix("data:").trim()
                    if (dataStr.isEmpty() || dataStr == "[DONE]") continue

                    val chunk = json.parseToJsonElement(dataStr).jsonObject
                    val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject

                    chunk["continuationToken"]?.jsonPrimitive?.contentOrNull?.let { nextToken = it }
                    candidate?.get("continuationToken")?.jsonPrimitive?.contentOrNull?.let { nextToken = it }

                    val parts = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray
                    parts?.forEach { partElem ->
                        val part = partElem.jsonObject
                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        val text = part["text"]?.jsonPrimitive?.contentOrNull

                        if (text != null) {
                            if (isThought) emit(StreamChunkEvent(thoughtDelta = text))
                            else emit(StreamChunkEvent(textDelta = text))
                        }

                        part["executableCode"]?.jsonObject?.let { exec ->
                            emit(
                                StreamChunkEvent(
                                    codeBlock = CodeExecutionBlock(
                                        language = exec["language"]?.jsonPrimitive?.contentOrNull ?: "PYTHON",
                                        code = exec["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    )
                                )
                            )
                        }

                        part["codeExecutionResult"]?.jsonObject?.let { res ->
                            emit(
                                StreamChunkEvent(
                                    codeBlock = CodeExecutionBlock(
                                        language = "RESULT",
                                        code = "",
                                        output = res["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        outcome = res["outcome"]?.jsonPrimitive?.contentOrNull
                                    )
                                )
                            )
                        }

                        part["inlineData"]?.jsonObject?.let { inline ->
                            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                            val b64 = inline["data"]?.jsonPrimitive?.contentOrNull
                            if (b64 != null) {
                                val file = saveBase64ToCache(b64, mime)
                                val mType = when {
                                    mime.startsWith("video/") -> MediaType.VIDEO
                                    mime.startsWith("audio/") -> MediaType.AUDIO
                                    else -> MediaType.IMAGE
                                }
                                emit(
                                    StreamChunkEvent(
                                        generatedImage = GeneratedMedia(
                                            type = mType,
                                            file = file,
                                            mimeType = mime
                                        )
                                    )
                                )
                            }
                        }
                    }

                    chunk["usageMetadata"]?.jsonObject?.let { usage ->
                        emit(
                            StreamChunkEvent(
                                usage = TokenUsage(
                                    promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                                    thoughtsTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                                    outputTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                                    cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                                )
                            )
                        )
                    }
                }
            }

            continuationToken = nextToken
            segmentsRemaining--
        } while (!continuationToken.isNullOrBlank() && segmentsRemaining > 0)
    }.flowOn(Dispatchers.IO)

    private fun buildGenerateContentPayload(
        modelId: String,
        history: List<ChatMessage>,
        ideWorkspaceFiles: List<IdeFile>,
        includeWorkspace: Boolean,
        config: Gemini4Config,
        continuationToken: String?
    ): JsonObject = buildJsonObject {
        if (config.systemInstruction.isNotBlank()) {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", config.systemInstruction) }
                }
            }
        }

        putJsonArray("contents") {
            history.forEachIndexed { index, msg ->
                val isLastUser = msg.isUser && index == history.lastIndex
                addJsonObject {
                    put("role", if (msg.isUser) "user" else "model")
                    putJsonArray("parts") {
                        if (isLastUser && includeWorkspace && ideWorkspaceFiles.isNotEmpty()) {
                            val snapshot = buildString {
                                appendLine("### CURRENT IDE WORKSPACE FILES")
                                ideWorkspaceFiles.forEach { f ->
                                    appendLine("\n--- FILE: ${f.name} (${f.language}) ---")
                                    appendLine("```${f.language}\n${f.content}\n```")
                                }
                            }
                            addJsonObject { put("text", snapshot) }
                        }

                        msg.attachments.forEach { att ->
                            when {
                                att.extractedText != null -> {
                                    addJsonObject {
                                        put("text", "Attached file `${att.displayName}`:\n```\n${att.extractedText}\n```")
                                    }
                                }
                                att.uploadedFileUri != null -> {
                                    addJsonObject {
                                        putJsonObject("fileData") {
                                            put("mimeType", att.mimeType)
                                            put("fileUri", att.uploadedFileUri)
                                        }
                                    }
                                }
                            }
                        }

                        if (msg.text.isNotBlank()) {
                            addJsonObject { put("text", msg.text) }
                        }
                    }
                }
            }
        }

        if (config.enableCodeExecution || config.enableGoogleSearch) {
            putJsonArray("tools") {
                if (config.enableCodeExecution) addJsonObject { putJsonObject("codeExecution") {} }
                if (config.enableGoogleSearch) addJsonObject { putJsonObject("googleSearch") {} }
            }
        }

        putJsonObject("generationConfig") {
            val effectiveMax = if (config.enableLongDecode && config.maxOutputTokens > 65536) {
                config.maxOutputTokens
            } else {
                config.maxOutputTokens.coerceAtMost(65536)
            }
            put("maxOutputTokens", effectiveMax)
            if (config.enableJsonMode) {
                put("responseMimeType", "application/json")
            }

            if (modelId.contains("gemini-4") || modelId.contains("gemini-3")) {
                val safeLevel = if (modelId.contains("carbon") && config.thinkingLevel == ThinkingLevel.XHIGH) {
                    ThinkingLevel.HIGH
                } else {
                    config.thinkingLevel
                }
                putJsonObject("thinkingConfig") {
                    put("thinkingLevel", safeLevel.apiValue)
                    put("includeThoughts", true)
                }
            }
        }

        if (!continuationToken.isNullOrBlank()) {
            put("continuationToken", continuationToken)
        }
    }

    suspend fun generateImage(
        apiKey: String,
        modelId: String,
        prompt: String,
        referenceFiles: List<AttachedFile>,
        imageConfig: ImageGenConfig
    ): Pair<String, List<GeneratedMedia>> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        referenceFiles.forEach { att ->
                            if (att.uploadedFileUri != null) {
                                addJsonObject {
                                    putJsonObject("fileData") {
                                        put("mimeType", att.mimeType)
                                        put("fileUri", att.uploadedFileUri)
                                    }
                                }
                            }
                        }
                        addJsonObject { put("text", prompt) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add("TEXT")
                    add("IMAGE")
                }
                putJsonObject("imageConfig") {
                    put("aspectRatio", imageConfig.aspectRatio)
                    put("imageSize", imageConfig.resolution)
                }
            }
        }

        val req = Request.Builder()
            .url("$baseUrl/v1beta/models/$modelId:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Image generation failed (${resp.code}): $body")
            }
            val root = json.parseToJsonElement(body).jsonObject
            val parts = root["candidates"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()

            val textOut = StringBuilder()
            val images = mutableListOf<GeneratedMedia>()

            parts.forEach { p ->
                val obj = p.jsonObject
                obj["text"]?.jsonPrimitive?.contentOrNull?.let { textOut.appendLine(it) }
                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                    val b64 = inline["data"]?.jsonPrimitive?.contentOrNull ?: return@let
                    val file = saveBase64ToCache(b64, mime)
                    images += GeneratedMedia(type = MediaType.IMAGE, file = file, mimeType = mime)
                }
            }
            textOut.toString().trim() to images
        }
    }

    suspend fun generateVideo(
        apiKey: String,
        modelId: String,
        prompt: String,
        referenceFiles: List<AttachedFile>,
        videoConfig: VideoGenConfig
    ): GeneratedMedia = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", modelId)
            if (referenceFiles.isEmpty()) {
                put("input", prompt)
            } else {
                putJsonArray("input") {
                    referenceFiles.forEach { att ->
                        if (att.uploadedFileUri != null) {
                            addJsonObject {
                                put("type", if (att.mimeType.startsWith("video/")) "video" else "image")
                                put("uri", att.uploadedFileUri)
                                put("mime_type", att.mimeType)
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    }
                }
            }
            videoConfig.previousInteractionId?.let { put("previous_interaction_id", it) }
            putJsonObject("response_format") {
                put("type", "video")
                put("aspect_ratio", videoConfig.aspectRatio)
                put("resolution", videoConfig.resolution)
                put("duration_seconds", videoConfig.durationSeconds)
            }
        }

        val resultObj = executeInteractionAndPoll(apiKey, payload)
        extractMediaFromInteraction(apiKey, resultObj, MediaType.VIDEO, "video/mp4")
    }

    suspend fun generateMusic(
        apiKey: String,
        modelId: String,
        prompt: String,
        referenceFiles: List<AttachedFile>
    ): GeneratedMedia = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", modelId)
            if (referenceFiles.isEmpty()) {
                put("input", prompt)
            } else {
                putJsonArray("input") {
                    referenceFiles.forEach { att ->
                        if (att.uploadedFileUri != null) {
                            addJsonObject {
                                put("type", "image")
                                put("uri", att.uploadedFileUri)
                                put("mime_type", att.mimeType)
                            }
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    }
                }
            }
        }

        val resultObj = executeInteractionAndPoll(apiKey, payload)
        extractMediaFromInteraction(apiKey, resultObj, MediaType.AUDIO, "audio/mpeg")
    }

    private suspend fun executeInteractionAndPoll(apiKey: String, payload: JsonObject): JsonObject {
        val createReq = Request.Builder()
            .url("$baseUrl/v1beta/interactions")
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        var current = client.newCall(createReq).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Interaction failed (${resp.code}): $body")
            }
            json.parseToJsonElement(body).jsonObject
        }

        val interactionId = current["id"]?.jsonPrimitive?.contentOrNull
        var status = current["status"]?.jsonPrimitive?.contentOrNull

        var attempts = 0
        while ((status == "in_progress" || status == "queued" || status == "running") &&
            interactionId != null && attempts < 80
        ) {
            delay(3000)
            attempts++
            val pollReq = Request.Builder()
                .url("$baseUrl/v1beta/interactions/$interactionId")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            current = client.newCall(pollReq).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Polling interaction failed (${resp.code}): $body")
                }
                json.parseToJsonElement(body).jsonObject
            }
            status = current["status"]?.jsonPrimitive?.contentOrNull
        }

        if (status == "failed" || status == "cancelled") {
            throw IllegalStateException("Generation $status: ${current["error"]}")
        }
        return current
    }

    private fun extractMediaFromInteraction(
        apiKey: String,
        obj: JsonObject,
        expectedType: MediaType,
        defaultMime: String
    ): GeneratedMedia {
        val interactionId = obj["id"]?.jsonPrimitive?.contentOrNull
        val textParts = mutableListOf<String>()
        var foundData: String? = null
        var foundUri: String? = null
        var foundMime: String = defaultMime

        val targetKey = if (expectedType == MediaType.VIDEO) "video" else "audio"

        obj["outputs"]?.jsonArray?.forEach { elem ->
            val item = elem.jsonObject
            val type = item["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when {
                type == "text" -> item["text"]?.jsonPrimitive?.contentOrNull?.let(textParts::add)
                type == targetKey || type.startsWith(targetKey) -> {
                    foundData = item["data"]?.jsonPrimitive?.contentOrNull ?: foundData
                    foundUri = item["uri"]?.jsonPrimitive?.contentOrNull ?: foundUri
                    foundMime = item["mime_type"]?.jsonPrimitive?.contentOrNull
                        ?: item["mimeType"]?.jsonPrimitive?.contentOrNull
                        ?: foundMime
                }
            }
        }

        obj["output_$targetKey"]?.jsonObject?.let { block ->
            foundData = block["data"]?.jsonPrimitive?.contentOrNull ?: foundData
            foundUri = block["uri"]?.jsonPrimitive?.contentOrNull ?: foundUri
            foundMime = block["mime_type"]?.jsonPrimitive?.contentOrNull ?: foundMime
        }
        obj["output_text"]?.jsonPrimitive?.contentOrNull?.let(textParts::add)

        val file = when {
            foundData != null -> saveBase64ToCache(foundData!!, foundMime)
            foundUri != null -> downloadUriToCache(apiKey, foundUri!!, foundMime)
            else -> throw IllegalStateException("No $targetKey output returned in interaction response.")
        }

        return GeneratedMedia(
            type = expectedType,
            file = file,
            mimeType = foundMime,
            captionOrLyrics = textParts.joinToString("\n").ifBlank { null },
            interactionId = interactionId
        )
    }

    private fun saveBase64ToCache(base64: String, mimeType: String): File {
        val ext = when {
            mimeType.contains("mp4") -> "mp4"
            mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
            mimeType.contains("wav") -> "wav"
            mimeType.contains("jpeg") || mimeType.contains("jpg") -> "jpg"
            mimeType.contains("webp") -> "webp"
            else -> "png"
        }
        val out = File(context.cacheDir, "gen_${System.currentTimeMillis()}.$ext")
        out.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        return out
    }

    private fun downloadUriToCache(apiKey: String, uri: String, mimeType: String): File {
        val ext = if (mimeType.contains("mp4")) "mp4" else if (mimeType.contains("mp3") || mimeType.contains("mpeg")) "mp3" else "bin"
        val req = Request.Builder()
            .url(uri)
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        val out = File(context.cacheDir, "gen_${System.currentTimeMillis()}.$ext")
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Media download failed (${resp.code})")
            out.writeBytes(resp.body!!.bytes())
        }
        return out
    }
}
