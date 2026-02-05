package com.fomovoi.core.transcription

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discovers available speech models from Hugging Face at runtime.
 */
class ModelDiscoveryService {

    companion object {
        private const val TAG = "ModelDiscoveryService"
        private const val HF_API_BASE = "https://huggingface.co/api/models"

        // Known Whisper model repositories on Hugging Face
        private val WHISPER_REPOS = listOf(
            WhisperRepoConfig(
                repoId = "csukuangfj/sherpa-onnx-whisper-tiny.en",
                modelId = "whisper-tiny-en",  // Consistent with static catalog
                modelType = SpeechModelType.WHISPER_TINY,
                language = SpeechLanguage.ENGLISH,
                prefix = "tiny.en"
            ),
            WhisperRepoConfig(
                repoId = "csukuangfj/sherpa-onnx-whisper-small.en",
                modelId = "whisper-small-en",  // Consistent with static catalog
                modelType = SpeechModelType.WHISPER_SMALL,
                language = SpeechLanguage.ENGLISH,
                prefix = "small.en"
            )
        )
    }

    private data class WhisperRepoConfig(
        val repoId: String,
        val modelId: String,
        val modelType: SpeechModelType,
        val language: SpeechLanguage,
        val prefix: String
    )

    /**
     * Discovers all available models from Hugging Face.
     * Returns a list of SpeechModels with accurate file sizes.
     */
    suspend fun discoverModels(): List<SpeechModel> = withContext(Dispatchers.IO) {
        val models = mutableListOf<SpeechModel>()

        for (config in WHISPER_REPOS) {
            try {
                val model = fetchModelInfo(config)
                if (model != null) {
                    models.add(model)
                    Log.d(TAG, "Discovered model: ${model.displayName} (${model.totalSizeMB}MB)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to discover model ${config.repoId}: ${e.message}")
            }
        }

        models
    }

    private fun fetchModelInfo(config: WhisperRepoConfig): SpeechModel? {
        val apiUrl = "$HF_API_BASE/${config.repoId}/tree/main"
        Log.d(TAG, "Fetching model info from: $apiUrl")

        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Fomovoi-Android")

        try {
            val response = connection.inputStream.bufferedReader().readText()
            val files = JSONArray(response)

            val modelFiles = mutableListOf<ModelFile>()
            val encoderFile = "${config.prefix}-encoder.int8.onnx"
            val decoderFile = "${config.prefix}-decoder.int8.onnx"
            val tokensFile = "${config.prefix}-tokens.txt"

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val path = file.getString("path")
                val size = file.optLong("size", -1)

                // For LFS files, use the lfs.size
                val lfs = file.optJSONObject("lfs")
                val actualSize = lfs?.optLong("size", size) ?: size

                when (path) {
                    encoderFile -> modelFiles.add(ModelFile(path, actualSize))
                    decoderFile -> modelFiles.add(ModelFile(path, actualSize))
                    tokensFile -> modelFiles.add(ModelFile(path, actualSize))
                }
            }

            if (modelFiles.size < 3) {
                Log.w(TAG, "Incomplete model files for ${config.repoId}: found ${modelFiles.size}/3")
                return null
            }

            return SpeechModel(
                id = config.modelId,
                type = config.modelType,
                language = config.language,
                baseUrl = "https://huggingface.co/${config.repoId}/resolve/main",
                files = modelFiles
            )

        } finally {
            connection.disconnect()
        }
    }
}
