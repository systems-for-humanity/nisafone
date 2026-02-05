package app.s4h.fomovoi.feature.recording

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidTitlePrefixRepository(context: Context) : TitlePrefixRepository {

    companion object {
        private const val PREFS_NAME = "title_prefix_prefs"
        private const val KEY_PREFIXES = "prefixes"
        private const val KEY_SELECTED = "selected_prefix"
        private const val DEFAULT_PREFIX = "Recording"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _prefixes = MutableStateFlow(loadPrefixes())
    override val prefixes: StateFlow<List<String>> = _prefixes.asStateFlow()

    private val _selectedPrefix = MutableStateFlow(loadSelectedPrefix())
    override val selectedPrefix: StateFlow<String> = _selectedPrefix.asStateFlow()

    private fun loadPrefixes(): List<String> {
        val saved = prefs.getStringSet(KEY_PREFIXES, null)
        return if (saved.isNullOrEmpty()) {
            listOf(DEFAULT_PREFIX)
        } else {
            // Ensure default is always first
            val list = saved.toMutableList()
            if (!list.contains(DEFAULT_PREFIX)) {
                list.add(0, DEFAULT_PREFIX)
            }
            list.sortedWith(compareBy { it != DEFAULT_PREFIX })
        }
    }

    private fun loadSelectedPrefix(): String {
        return prefs.getString(KEY_SELECTED, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
    }

    override fun addPrefix(prefix: String) {
        val trimmed = prefix.trim()
        if (trimmed.isBlank() || _prefixes.value.contains(trimmed)) return

        val updated = _prefixes.value + trimmed
        _prefixes.value = updated
        savePrefixes(updated)
        selectPrefix(trimmed)
    }

    override fun removePrefix(prefix: String) {
        if (prefix == DEFAULT_PREFIX) return // Can't remove default

        val updated = _prefixes.value - prefix
        _prefixes.value = updated
        savePrefixes(updated)

        if (_selectedPrefix.value == prefix) {
            selectPrefix(DEFAULT_PREFIX)
        }
    }

    override fun selectPrefix(prefix: String) {
        if (!_prefixes.value.contains(prefix)) return
        _selectedPrefix.value = prefix
        prefs.edit().putString(KEY_SELECTED, prefix).apply()
    }

    private fun savePrefixes(prefixes: List<String>) {
        prefs.edit().putStringSet(KEY_PREFIXES, prefixes.toSet()).apply()
    }
}
