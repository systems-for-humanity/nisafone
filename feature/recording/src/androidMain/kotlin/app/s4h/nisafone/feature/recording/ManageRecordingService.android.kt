package app.s4h.nisafone.feature.recording

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

private val logger = Logger.withTag("ManageRecordingService")

@Composable
actual fun ManageRecordingService(isRecording: Boolean) {
    val context = LocalContext.current

    DisposableEffect(isRecording) {
        if (isRecording) {
            try {
                val serviceClass = Class.forName("app.s4h.nisafone.android.RecordingService")
                val intent = Intent(context, serviceClass)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                logger.e(e) { "Failed to start foreground recording service" }
            }
        } else {
            try {
                val serviceClass = Class.forName("app.s4h.nisafone.android.RecordingService")
                val intent = Intent(context, serviceClass)
                context.stopService(intent)
            } catch (_: ClassNotFoundException) {
                // Service class not available
            }
        }
        onDispose {
            try {
                val serviceClass = Class.forName("app.s4h.nisafone.android.RecordingService")
                val intent = Intent(context, serviceClass)
                context.stopService(intent)
            } catch (_: ClassNotFoundException) {
                // Service class not available
            }
        }
    }
}
