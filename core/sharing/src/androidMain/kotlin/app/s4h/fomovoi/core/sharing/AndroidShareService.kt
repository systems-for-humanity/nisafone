package app.s4h.fomovoi.core.sharing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import co.touchlab.kermit.Logger
import app.s4h.fomovoi.core.transcription.TranscriptionResult

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
            appendLine(result.formattedText)
        }

        return shareText(formattedText, "Fomovoi Transcription")
    }

    override suspend fun shareToApp(text: String, target: ShareTarget): ShareResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                target.packageName?.let { setPackage(it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            ShareResult.Success
        } catch (e: Exception) {
            logger.e(e) { "Failed to share to app: ${target.name}" }
            ShareResult.Error(e.message ?: "Failed to share to ${target.name}")
        }
    }

    override fun getAvailableTargets(): List<ShareTarget> {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }

        val packageManager = context.packageManager
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        return resolveInfos.mapNotNull { resolveInfo ->
            try {
                ShareTarget(
                    id = resolveInfo.activityInfo.packageName,
                    name = resolveInfo.loadLabel(packageManager).toString(),
                    packageName = resolveInfo.activityInfo.packageName
                )
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.packageName }
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
