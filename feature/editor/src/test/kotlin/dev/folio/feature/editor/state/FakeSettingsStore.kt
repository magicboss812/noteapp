package dev.folio.feature.editor.state

import dev.folio.core.common.Outcome
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** In-memory [SettingsStore]; [writes] counts stored values. */
class FakeSettingsStore : SettingsStore {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())
    var writes = 0
        private set

    /** The raw stored value of [key]. */
    fun raw(key: String): String? = values.value[key]

    override fun read(key: String): Flow<String?> = values.map { it[key] }.distinctUntilChanged()

    override suspend fun write(
        key: String,
        value: String,
    ): Outcome<Unit> {
        writes++
        values.value += key to value
        return Outcome.Success(Unit)
    }
}
