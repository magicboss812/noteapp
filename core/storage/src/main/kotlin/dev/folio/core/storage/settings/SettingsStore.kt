package dev.folio.core.storage.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.map
import dev.folio.core.common.outcomeOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App settings as string values by key (DataStore Preferences in `files/datastore/`, never in the library).
 * Callers own the value format (usually JSON with defaults for missing fields).
 */
interface SettingsStore {
    /** The value of [key], null while unset; emits again when it changes. */
    fun read(key: String): Flow<String?>

    /** Stores [value] under [key]; finishes even if the caller is cancelled meanwhile. */
    suspend fun write(
        key: String,
        value: String,
    ): Outcome<Unit>
}

/** [SettingsStore] over one Preferences [store]; a read failure reads as "unset" (the callers' defaults). */
@Singleton
internal class DataStoreSettingsStore(
    private val store: DataStore<Preferences>,
) : SettingsStore {
    // A corrupt settings file resets to defaults instead of failing every read.
    @Inject
    constructor(
        @ApplicationContext context: Context,
        dispatchers: FolioDispatchers,
    ) : this(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(SupervisorJob() + dispatchers.io + LogErrors),
        ) { context.preferencesDataStoreFile(FILE_NAME) },
    )

    private val data: Flow<Preferences> =
        store.data.catch { e ->
            if (e !is IOException) throw e
            FolioLog.w(TAG, "read failed, using defaults", e)
            emit(emptyPreferences())
        }

    override fun read(key: String): Flow<String?> {
        val prefKey = stringPreferencesKey(key)
        return data.map { it[prefKey] }.distinctUntilChanged()
    }

    override suspend fun write(
        key: String,
        value: String,
    ): Outcome<Unit> {
        val prefKey = stringPreferencesKey(key)
        // Settings change right before a screen leaves; a cancelled caller must not drop the write.
        val result = withContext(NonCancellable) { outcomeOf("write $key") { store.edit { it[prefKey] = value } }.map { } }
        if (result is Outcome.Failure) FolioLog.w(TAG, result.message, result.cause)
        return result
    }

    companion object {
        /** Preferences file name (without the `.preferences_pb` suffix). */
        const val FILE_NAME = "settings"
        private const val TAG = "SettingsStore"
        private val LogErrors = CoroutineExceptionHandler { _, e -> FolioLog.e(TAG, "settings task failed", e) }
    }
}
