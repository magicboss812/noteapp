package dev.folio.core.text.fonts

import androidx.annotation.WorkerThread
import dev.folio.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The font registry text is laid out with (07-text-engine.md#fonts): the bundled families plus the fonts
 * imported into [store]. [sync] and [import] publish a new [registry]; the layouter drops its cached layouts
 * when it sees one. Without a store (tests) only the bundled families exist. Blocking: call on the io dispatcher.
 */
class FontLibrary(
    private val store: CustomFontStore?,
) {
    private val state = MutableStateFlow(FontRegistry.Bundled)

    /** The current registry; a new instance after every change. */
    val registry: StateFlow<FontRegistry> = state.asStateFlow()

    /** The registry now. */
    fun current(): FontRegistry = state.value

    /** Installs fonts from the library folder (imported on another device), then reloads the registry. */
    @WorkerThread
    fun sync(): Outcome<Int> {
        val fonts = store ?: return Outcome.Success(0)
        val installed = fonts.syncFromLibrary()
        state.value = fonts.registry()
        return installed
    }

    /** Imports one `.ttf`/`.otf` file's [bytes] and reloads the registry. */
    @WorkerThread
    fun import(bytes: ByteArray): Outcome<ImportedFont> {
        val fonts = store ?: return Outcome.Failure("no font store")
        val imported = fonts.import(bytes)
        if (imported is Outcome.Success) state.value = fonts.registry()
        return imported
    }
}
