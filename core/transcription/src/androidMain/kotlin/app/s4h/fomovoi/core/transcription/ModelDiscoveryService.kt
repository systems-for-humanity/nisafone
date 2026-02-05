package app.s4h.fomovoi.core.transcription

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Discovers available speech models from Hugging Face at runtime.
 * Dynamically queries for all sherpa-onnx-whisper models instead of using a hardcoded list.
 */
class ModelDiscoveryService {

    companion object {
        private const val TAG = "ModelDiscoveryService"
        private const val HF_API_BASE = "https://huggingface.co/api/models"
        private const val HF_SEARCH_URL = "https://huggingface.co/api/models?author=csukuangfj&search=sherpa-onnx-whisper&limit=100"
    }

    /**
     * Discovers all available Whisper models from Hugging Face.
     * Returns a list of SpeechModels with accurate file sizes.
     */
    suspend fun discoverModels(): List<SpeechModel> = withContext(Dispatchers.IO) {
        val models = mutableListOf<SpeechModel>()

        try {
            // First, search for all sherpa-onnx-whisper repositories
            val repos = searchWhisperRepos()
            Log.d(TAG, "Found ${repos.size} Whisper repositories")

            for (repoId in repos) {
                try {
                    val model = fetchModelFromRepo(repoId)
                    if (model != null) {
                        models.add(model)
                        Log.d(TAG, "Discovered model: ${model.displayName} (${model.totalSizeMB}MB)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch model info for $repoId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search for models: ${e.message}")
        }

        // Sort by model size (smaller first for better UX)
        models.sortedBy { it.totalSizeBytes }
    }

    /**
     * Search HuggingFace for all sherpa-onnx-whisper repositories.
     */
    private fun searchWhisperRepos(): List<String> {
        Log.d(TAG, "Searching for Whisper repos: $HF_SEARCH_URL")

        val connection = URL(HF_SEARCH_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Fomovoi-Android")

        try {
            val response = connection.inputStream.bufferedReader().readText()
            val reposArray = JSONArray(response)

            val repos = mutableListOf<String>()
            for (i in 0 until reposArray.length()) {
                val repo = reposArray.getJSONObject(i)
                val modelId = repo.getString("modelId")
                // Only include whisper models (filter out streaming/zipformer models)
                if (modelId.contains("whisper", ignoreCase = true)) {
                    repos.add(modelId)
                }
            }
            return repos
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetch model info from a specific repository.
     * Parses the repo name to determine model type and language.
     */
    private fun fetchModelFromRepo(repoId: String): SpeechModel? {
        // Parse repo name to extract model info
        // Format: csukuangfj/sherpa-onnx-whisper-{size}[.{lang}]
        val repoName = repoId.substringAfter("/")
        if (!repoName.startsWith("sherpa-onnx-whisper-")) return null

        val modelSuffix = repoName.removePrefix("sherpa-onnx-whisper-")
        val (modelSize, language) = parseModelSuffix(modelSuffix)

        if (modelSize == null) {
            Log.w(TAG, "Could not parse model size from: $modelSuffix")
            return null
        }

        // Determine file prefix based on model suffix
        val prefix = modelSuffix

        // Fetch file list from repo
        val apiUrl = "$HF_API_BASE/$repoId/tree/main"
        Log.d(TAG, "Fetching model files from: $apiUrl")

        val connection = URL(apiUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "Fomovoi-Android")

        try {
            val response = connection.inputStream.bufferedReader().readText()
            val files = JSONArray(response)

            val modelFiles = mutableListOf<ModelFile>()
            val encoderFile = "$prefix-encoder.int8.onnx"
            val decoderFile = "$prefix-decoder.int8.onnx"
            val tokensFile = "$prefix-tokens.txt"

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
                Log.w(TAG, "Incomplete model files for $repoId: found ${modelFiles.size}/3 (looking for $prefix-*.onnx)")
                return null
            }

            val modelId = "whisper-$modelSuffix"
            val modelType = getModelType(modelSize)

            return SpeechModel(
                id = modelId,
                type = modelType,
                language = language,
                baseUrl = "https://huggingface.co/$repoId/resolve/main",
                files = modelFiles
            )

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Parse model suffix to extract size and language.
     * Examples: "tiny.en" -> ("tiny", ENGLISH), "large-v3" -> ("large-v3", MULTILINGUAL)
     */
    private fun parseModelSuffix(suffix: String): Pair<String?, SpeechLanguage> {
        // Check if it's an English-only model (ends with .en)
        return if (suffix.endsWith(".en")) {
            val size = suffix.removeSuffix(".en")
            Pair(size, SpeechLanguage.ENGLISH)
        } else {
            // Multilingual model
            Pair(suffix, SpeechLanguage.MULTILINGUAL)
        }
    }

    /**
     * Map model size string to SpeechModelType.
     */
    private fun getModelType(size: String): SpeechModelType {
        return when {
            size == "tiny" -> SpeechModelType.WHISPER_TINY
            size == "base" -> SpeechModelType.WHISPER_BASE
            size == "small" -> SpeechModelType.WHISPER_SMALL
            size == "medium" -> SpeechModelType.WHISPER_MEDIUM
            size.startsWith("large") -> SpeechModelType.WHISPER_LARGE
            size == "turbo" -> SpeechModelType.WHISPER_TURBO
            size.startsWith("distil") -> SpeechModelType.WHISPER_DISTIL
            else -> SpeechModelType.WHISPER_OTHER
        }
    }
}
