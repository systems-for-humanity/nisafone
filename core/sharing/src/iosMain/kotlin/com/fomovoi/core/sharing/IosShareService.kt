package com.fomovoi.core.sharing

import co.touchlab.kermit.Logger
import com.fomovoi.core.transcription.TranscriptionResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication

actual fun createShareService(): ShareService = IosShareService()

@OptIn(ExperimentalForeignApi::class)
class IosShareService : ShareService {

    private val logger = Logger.withTag("IosShareService")

    override suspend fun shareText(text: String, title: String?): ShareResult {
        return try {
            val items = listOf(text)
            val activityViewController = UIActivityViewController(
                activityItems = items,
                applicationActivities = null
            )

            val rootViewController = UIApplication.sharedApplication.keyWindow?.rootViewController

            rootViewController?.presentViewController(
                activityViewController,
                animated = true,
                completion = null
            )

            ShareResult.Success
        } catch (e: Exception) {
            logger.e(e) { "Failed to share text" }
            ShareResult.Error(e.message ?: "Failed to share")
        }
    }

    override suspend fun shareTranscription(result: TranscriptionResult): ShareResult {
        val formattedText = buildString {
            appendLine("Transcription - ${result.createdAt}")
            appendLine("Duration: ${formatDuration(result.durationMs)}")
            appendLine()
            appendLine(result.formattedText)
        }

        return shareText(formattedText, "Fomovoi Transcription")
    }

    override suspend fun shareToApp(text: String, target: ShareTarget): ShareResult {
        // iOS doesn't support direct app targeting like Android
        // Fall back to general share sheet
        return shareText(text)
    }

    override fun getAvailableTargets(): List<ShareTarget> {
        // iOS doesn't provide a way to enumerate share targets
        // Return common AI assistant apps that users might want to share to
        return listOf(
            ShareTarget(id = "chatgpt", name = "ChatGPT"),
            ShareTarget(id = "claude", name = "Claude"),
            ShareTarget(id = "notes", name = "Notes"),
            ShareTarget(id = "mail", name = "Mail"),
            ShareTarget(id = "messages", name = "Messages")
        )
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = (durationMs / 1000) % 60
        val minutes = (durationMs / (1000 * 60)) % 60
        val hours = durationMs / (1000 * 60 * 60)

        return if (hours > 0) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "$minutes:${seconds.toString().padStart(2, '0')}"
        }
    }
}
