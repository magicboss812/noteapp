package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.Orientation
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** In-memory [SettingsStore]. */
internal class FakeSettingsStore : SettingsStore {
    val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override fun read(key: String): Flow<String?> = values.map { it[key] }

    override suspend fun write(
        key: String,
        value: String,
    ): Outcome<Unit> {
        values.value = values.value + (key to value)
        return Outcome.Success(Unit)
    }
}

class NewNoteDefaultsStoreTest {
    private val settings = FakeSettingsStore()
    private val store = NewNoteDefaultsStore(settings)

    @Test
    fun load_nothingStored_isTheDefaultForm() =
        runTest {
            assertThat(store.load("Untitled")).isEqualTo(NewNoteForm("Untitled"))
        }

    @Test
    fun save_thenLoad_restoresTheChoicesButNotTheTitle() =
        runTest {
            val form =
                NewNoteForm(
                    "Old title",
                    PaperSize.A5,
                    Orientation.LANDSCAPE,
                    NotePageType.INFINITE,
                    TemplateKind.DOTTED,
                    14.17f,
                    NotePaper.DARK.argb,
                )

            store.save(form)

            assertThat(store.load("New title")).isEqualTo(form.copy(title = "New title"))
        }

    @Test
    fun load_unreadableValue_fallsBackToDefaults() =
        runTest {
            settings.write(NewNoteDefaultsStore.KEY, "{\"size\":\"A9\"}")

            assertThat(store.load("t")).isEqualTo(NewNoteForm("t"))
        }

    @Test
    fun load_missingFieldsReadAsDefaults() =
        runTest {
            settings.write(NewNoteDefaultsStore.KEY, "{\"template\":\"GRID\",\"future\":1}")

            assertThat(store.load("t")).isEqualTo(NewNoteForm("t", template = TemplateKind.GRID))
        }
}
