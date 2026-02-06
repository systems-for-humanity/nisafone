package app.s4h.nisafone.core.sharing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import co.touchlab.kermit.Logger
import app.s4h.nisafone.core.transcription.TranscriptionResult

actual fun createShareService(): ShareService {
    throw IllegalStateException("Use createAndroidShareService with Context")
}

fun createAndroidShareService(context: Context): ShareService {
    return AndroidShareService(context)
}

class AndroidShareService(
    private val context: Context
) : ShareService {

    private val logger = Logger.withTag("AndroidShareService")

    override suspend fun shareText(text: String, title: String?): ShareResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chooserIntent = Intent.createChooser(intent, "Share transcription").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chooserIntent)
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
            // Use mailto: URI with all parameters encoded in the URI itself
            val mailtoUri = Uri.parse(
                "mailto:$to?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
            )

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = mailtoUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            ShareResult.Success
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
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
