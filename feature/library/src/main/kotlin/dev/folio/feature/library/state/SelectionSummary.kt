package dev.folio.feature.library.state

/** Every visible note is ticked (and there is at least one). */
fun allTicked(
    visible: List<LibraryDocItem>,
    selected: Set<String>,
) = visible.isNotEmpty() && visible.all { it.path in selected }

/** The ticked notes are all favorites (and at least one is ticked). */
fun allFavorite(
    visible: List<LibraryDocItem>,
    selected: Set<String>,
): Boolean {
    val picked = visible.filter { it.path in selected }
    return picked.isNotEmpty() && picked.all { it.favorite }
}
