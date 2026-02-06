package app.s4h.nisafone.core.sharing

import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.transcription.TranscriptionResult
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
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
            appendLine(result.utterances.joinToString("\n\n") { utterance ->
                "[${utterance.speaker.label}]: ${utterance.text}"
            })
        }

        return shareText(formattedText, "Nisafone Transcription")
    }

    override suspend fun sendEmail(to: String, subject: String, body: String): ShareResult {
        return try {
            // Encode the mailto URL
            val encodedSubject = subject.replace(" ", "%20")
            val encodedBody = body.replace(" ", "%20").replace("\n", "%0A")
            val mailtoUrl = "mailto:$to?subject=$encodedSubject&body=$encodedBody"

            val url = NSURL.URLWithString(mailtoUrl)
            if (url != null && UIApplication.sharedApplication.canOpenURL(url)) {
                UIApplication.sharedApplication.openURL(url)
                ShareResult.Success
            } else {
                // Fallback to share sheet
                logger.w { "Cannot open mailto URL, falling back to share" }
                shareText(body, subject)
            }
        } catch (e: Exception) {
            logger.e(e) { "Failed to send email" }
            ShareResult.Error(e.message ?: "Failed to send email")
        }
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
