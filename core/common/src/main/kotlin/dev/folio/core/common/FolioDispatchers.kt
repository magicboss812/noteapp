package dev.folio.core.common

import kotlinx.coroutines.CoroutineDispatcher

/** Named dispatchers (Kotlin rules). Built in :app; tests use core:testing's TestDispatchersRule. */
data class FolioDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val render: CoroutineDispatcher,
    val pdf: CoroutineDispatcher,
    val text: CoroutineDispatcher,
)
