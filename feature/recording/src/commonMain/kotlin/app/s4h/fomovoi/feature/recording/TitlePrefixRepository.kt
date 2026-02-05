package app.s4h.fomovoi.feature.recording

import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for managing recording title prefixes.
 */
interface TitlePrefixRepository {
    val prefixes: StateFlow<List<String>>
    val selectedPrefix: StateFlow<String>

    fun addPrefix(prefix: String)
    fun removePrefix(prefix: String)
    fun selectPrefix(prefix: String)
}
