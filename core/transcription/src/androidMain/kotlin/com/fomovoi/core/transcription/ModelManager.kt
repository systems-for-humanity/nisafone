package com.fomovoi.core.transcription

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages speech recognition model downloads and storage.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "speech-models"
        private const val PREFS_NAME = "model_manager_prefs"
        private const val PREF_SELECTED_MODEL = "selected_model"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelsBaseDir = File(context.filesDir, MODELS_DIR)

    private val _downloadingModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadingModels: StateFlow<Set<String>> = _downloadingModels.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    init {
        modelsBaseDir.mkdirs()
    }

    /**
     * Get all available models with their download status.
     */
    fun getAvailableModels(): List<SpeechModel> {
        return SpeechModelCatalog.allModels.map { model ->
            model.copy(isDownloaded = isModelDownloaded(model))
        }
    }

    /**
     * Check if a model is fully downloaded.
     */
    fun isModelDownloaded(model: SpeechModel): Boolean {
        val modelDir = getModelDir(model)
        if (!modelDir.exists()) return false
        return model.files.all { file ->
            val localFile = File(modelDir, file.name)
            localFile.exists() && localFile.length() == file.expectedSize
        }
    }

    /**
     * Get the directory for a specific model.
     */
    fun getModelDir(model: SpeechModel): File {
        return File(modelsBaseDir, model.id)
    }

    /**
     * Download a model.
     */
    suspend fun downloadModel(
        model: SpeechModel,
        onProgress: (Float) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelDownloaded(model)) {
            return@withContext Result.success(Unit)
        }

        _downloadingModels.update { it + model.id }
        _downloadProgress.update { it + (model.id to 0f) }

        try {
            val modelDir = getModelDir(model)
            modelDir.mkdirs()

            // Clean up partial downloads
            model.files.forEach { file ->
                val localFile = File(modelDir, file.name)
                if (localFile.exists() && localFile.length() != file.expectedSize) {
                    Log.d(TAG, "Removing incomplete file: ${file.name}")
                    localFile.delete()
                }
            }

            val totalSize = model.totalSizeBytes
            var downloadedSize = 0L

            model.files.forEach { file ->
                val localFile = File(modelDir, file.name)
                if (!localFile.exists()) {
                    Log.d(TAG, "Downloading ${file.name} for ${model.displayName}...")

                    downloadFile(
                        url = "${model.baseUrl}/${file.name}",
                        destination = localFile,
                        expectedSize = file.expectedSize,
                        onBytesDownloaded = { bytes ->
                            downloadedSize += bytes
                            val progress = downloadedSize.toFloat() / totalSize
                            _downloadProgress.update { it + (model.id to progress) }
                            onProgress(progress)
                        }
                    )

                    Log.d(TAG, "Downloaded ${file.name}")
                } else {
                    downloadedSize += file.expectedSize
                }
            }

            _downloadProgress.update { it + (model.id to 1f) }
            onProgress(1f)

            Log.d(TAG, "Model ${model.displayName} download complete")
            Result.success(Unit)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download model ${model.displayName}: ${e.message}", e)
            Result.failure(e)
        } finally {
            _downloadingModels.update { it - model.id }
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        expectedSize: Long,
        onBytesDownloaded: (Long) -> Unit
    ) {
        val tempFile = File(destination.parent, "${destination.name}.tmp")

        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Fomovoi-Android")

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        onBytesDownloaded(bytesRead.toLong())
                    }

                    if (totalBytesRead != expectedSize) {
                        throw Exception("Download incomplete: got $totalBytesRead bytes, expected $expectedSize")
                    }
                }
            }

            if (!tempFile.renameTo(destination)) {
                throw Exception("Failed to rename temp file")
            }

        } catch (e: Exception) {
            tempFile.delete()
            throw Exception("Failed to download from $url: ${e.message}", e)
        }
    }

    /**
     * Delete a downloaded model.
     */
    fun deleteModel(model: SpeechModel): Boolean {
        val modelDir = getModelDir(model)
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else {
            true
        }
    }

    /**
     * Get the currently selected model ID.
     */
    fun getSelectedModelId(): String? {
        return prefs.getString(PREF_SELECTED_MODEL, null)
    }

    /**
     * Set the selected model.
     */
    fun setSelectedModel(model: SpeechModel) {
        prefs.edit().putString(PREF_SELECTED_MODEL, model.id).apply()
    }

    /**
     * Get the currently selected model, or null if none selected or not downloaded.
     */
    fun getSelectedModel(): SpeechModel? {
        val modelId = getSelectedModelId() ?: return null
        val model = SpeechModelCatalog.getModelById(modelId) ?: return null
        return if (isModelDownloaded(model)) model else null
    }

    /**
     * Get total storage used by downloaded models.
     */
    fun getTotalStorageUsed(): Long {
        return modelsBaseDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }
}
