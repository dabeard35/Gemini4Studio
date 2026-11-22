package com.gemini4studio.data

import android.net.Uri
import java.io.File
import java.util.UUID

enum class StudioFeature(val label: String) {
    CHAT_AND_IDE("Chat + IDE"),
    IMAGE_GEN("Images"),
    VIDEO_GEN("Video"),
    MUSIC_GEN("Music")
}

enum class ThinkingLevel(val apiValue: String, val label: String) {
    LOW("low", "Low"),
    MEDIUM("medium", "Medium"),
    HIGH("high", "High"),
    XHIGH("xhigh", "XHigh")
}

data class DetectedModel(
    val id: String,
    val displayName: String,
    val description: String,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
    val supportedMethods: List<String>,
    val category: StudioFeature
)

data class AttachedFile(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadedFileUri: String? = null,
    val extractedText: String? = null
)

data class CodeExecutionBlock(
    val language: String,
    val code: String,
    val output: String? = null,
    val outcome: String? = null
)

data class GeneratedMedia(
    val id: String = UUID.randomUUID().toString(),
    val type: MediaType,
    val file: File,
    val mimeType: String,
    val captionOrLyrics: String? = null,
    val interactionId: String? = null
)

enum class MediaType { IMAGE, VIDEO, AUDIO }

data class TokenUsage(
    val promptTokens: Int = 0,
    val thoughtsTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedTokens: Int = 0
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String = "",
    val thinkingText: String = "",
    val attachments: List<AttachedFile> = emptyList(),
    val codeExecutions: List<CodeExecutionBlock> = emptyList(),
    val generatedMedia: List<GeneratedMedia> = emptyList(),
    val modelUsed: String? = null,
    val tokenUsage: TokenUsage? = null,
    val isStreaming: Boolean = false
)

data class IdeFile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val language: String,
    val content: String
)

data class Gemini4Config(
    val thinkingLevel: ThinkingLevel = ThinkingLevel.HIGH,
    val enableCodeExecution: Boolean = true,
    val enableGoogleSearch: Boolean = false,
    val enableJsonMode: Boolean = false,
    val enableLongDecode: Boolean = true,
    val maxOutputTokens: Int = 65536,
    val systemInstruction: String = "You are an expert senior software engineer powered by Gemini 4. Produce clean, complete, production-ready code using fenced code blocks."
)

data class ImageGenConfig(
    val aspectRatio: String = "1:1",
    val resolution: String = "2K"
)

data class VideoGenConfig(
    val aspectRatio: String = "16:9",
    val resolution: String = "720p",
    val durationSeconds: Int = 6,
    val previousInteractionId: String? = null
)
