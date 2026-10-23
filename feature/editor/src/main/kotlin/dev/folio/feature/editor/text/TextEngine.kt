package dev.folio.feature.editor.text

import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.FlowReflow
import javax.inject.Inject
import javax.inject.Singleton

/** The app-wide text engine of the editor: one layouter stack, so one block layout cache for every open note. */
@Singleton
class TextEngine
    @Inject
    constructor(
        /** Lays flows out over their frames. */
        val layouter: FlowLayouter,
        /** Plans the page changes an edited flow needs. */
        val reflow: FlowReflow,
    )
