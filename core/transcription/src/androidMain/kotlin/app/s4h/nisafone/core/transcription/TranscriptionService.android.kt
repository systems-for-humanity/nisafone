package app.s4h.nisafone.core.transcription

// Android wires SherpaOnnxTranscriptionService through Koin with a Context;
// this context-free actual exists only to satisfy the expect declaration
actual fun createTranscriptionService(): TranscriptionService {
    throw IllegalStateException("Use createSherpaOnnxTranscriptionService with Context")
}
