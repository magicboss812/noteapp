package dev.folio.core.common

import kotlinx.coroutines.CoroutineDispatcher

/** Named dispatchers (Kotlin rules). Provided by :app AppModule; tests use core:testing's TestDispatchersRule. */
data class FolioDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val render: CoroutineDispatcher,
    val pdf: CoroutineDispatcher,
    val text: CoroutineDispatcher,
)
