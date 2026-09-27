package dev.folio.core.storage

/**
 * Where the library lives, relative to shared storage (`/sdcard`).
 * Release: `Documents/Folio`, debug: `Documents/Folio-Debug` (BuildConfig in :app).
 */
data class LibraryConfig(
    val rootRelativePath: String,
)
