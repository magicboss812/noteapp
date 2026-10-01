package dev.folio.feature.library.state

import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.model.Orientation
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * The last new-note choices as JSON in the app settings (one key): they become the next defaults. The title is
 * not kept. Missing fields read as defaults and an unreadable value falls back to the defaults.
 */
class NewNoteDefaultsStore
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) {
        /** The stored choices as a form with [title], defaults while nothing is stored. */
        suspend fun load(title: String): NewNoteForm = settings.read(KEY).first()?.let { decode(it, title) } ?: NewNoteForm(title)

        /** Stores the choices of [form] (not its title). */
        suspend fun save(form: NewNoteForm): Outcome<Unit> = settings.write(KEY, encode(form))

        internal companion object {
            const val KEY = "library.newNoteDefaults"
            private const val TAG = "NewNoteDefaults"
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            fun encode(form: NewNoteForm): String =
                json.encodeToString(
                    DefaultsJson.serializer(),
                    DefaultsJson(1, form.size, form.orientation, form.pageType, form.template, form.spacingPt, form.paperArgb),
                )

            fun decode(
                text: String,
                title: String,
            ): NewNoteForm =
                try {
                    val d = json.decodeFromString(DefaultsJson.serializer(), text)
                    NewNoteForm(title, d.size, d.orientation, d.pageType, d.template, d.spacingPt, d.paperArgb)
                } catch (e: SerializationException) {
                    FolioLog.w(TAG, "stored new-note defaults unreadable, using defaults", e)
                    NewNoteForm(title)
                }
        }
    }

@Serializable
private data class DefaultsJson(
    val version: Int = 1,
    val size: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val pageType: NotePageType = NotePageType.FIXED,
    val template: TemplateKind = TemplateKind.LINED,
    val spacingPt: Float = NewNoteForm("").spacingPt,
    val paperArgb: Int = NotePaper.WHITE.argb,
)
