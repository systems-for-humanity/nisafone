package app.s4h.nisafone.core.sharing

import app.s4h.nisafone.core.transcription.TranscriptionResult

sealed class ShareResult {
    data object Success : ShareResult()
    data class Error(val message: String) : ShareResult()
    data object Cancelled : ShareResult()
}

interface ShareService {
    suspend fun shareText(text: String, title: String? = null): ShareResult
    suspend fun shareTranscription(result: TranscriptionResult): ShareResult

    /**
     * Compose an email with the given parameters.
     * Opens the device's email client with the email pre-populated.
     */
    suspend fun sendEmail(to: String, subject: String, body: String): ShareResult
}

expect fun createShareService(): ShareService
