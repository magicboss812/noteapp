package dev.folio.core.storage.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The DataStore-backed settings over a real preferences file. */
class DataStoreSettingsStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val file by lazy { File(temp.root, "settings.preferences_pb") }

    // DataStore allows one active instance per file: each "process" gets its own scope, cancelled before the next.
    private fun <T> withStore(block: suspend (SettingsStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.IO + Job())
            try {
                block(DataStoreSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file }))
            } finally {
                scope.cancel()
                scope.coroutineContext.job.join()
            }
        }

    @Test
    fun read_unsetKey_null() {
        assertThat(withStore { it.read("missing").first() }).isNull()
    }

    @Test
    fun write_thenNewInstance_readsSameValues() {
        withStore {
            assertThat(it.write("a", """{"x":1}""")).isEqualTo(Outcome.Success(Unit))
            assertThat(it.write("b", "second")).isEqualTo(Outcome.Success(Unit))
            assertThat(it.write("a", """{"x":2}""")).isEqualTo(Outcome.Success(Unit))
        }

        val values = withStore { listOf(it.read("a").first(), it.read("b").first()) }

        assertThat(values).containsExactly("""{"x":2}""", "second").inOrder()
    }
}
