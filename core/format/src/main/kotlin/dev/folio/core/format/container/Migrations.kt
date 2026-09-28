package dev.folio.core.format.container

import dev.folio.core.common.Outcome
import dev.folio.core.format.FormatError
import dev.folio.core.format.manifest.Manifest
import kotlinx.serialization.json.JsonObject

/** Upgrades a document from major version [from] to [to] (= from + 1), starting with the manifest JSON. */
interface Migration {
    /** Source major version. */
    val from: Int

    /** Target major version. */
    val to: Int

    /** Rewrites the raw manifest JSON. */
    fun migrateManifest(manifest: JsonObject): JsonObject
}

/** Ordered migrations up to [current] (04-file-format.md#versioning). v1 has none. */
class MigrationRegistry(
    private val migrations: List<Migration> = emptyList(),
    val current: Int = Manifest.CURRENT_VERSION,
) {
    init {
        migrations.forEach { require(it.to == it.from + 1 && it.to <= current) { "migration ${it.from}->${it.to} invalid" } }
        require(migrations.distinctBy { it.from }.size == migrations.size) { "duplicate migration" }
    }

    /**
     * Migrations to apply for a file of [version] in order. Newer than [current] fails with
     * [FormatError.FormatTooNew]; a gap in the chain fails with [FormatError.Corrupt].
     */
    fun plan(version: Int): Outcome<List<Migration>> {
        val byFrom = migrations.associateBy { it.from }
        val chain = generateSequence(byFrom[version]) { byFrom[it.to] }.takeWhile { it.from < current }.toList()
        val reached = chain.lastOrNull()?.to ?: version
        return when {
            version > current -> {
                Outcome.Failure(
                    "format version $version is newer than $current",
                    FormatError.FormatTooNew(version, current),
                )
            }

            reached != current -> {
                corrupt(FolioEntries.MANIFEST, "no migration from version $reached")
            }

            else -> {
                Outcome.Success(chain)
            }
        }
    }

    /** Registry with the migrations of this app version. */
    companion object {
        /** No migrations exist for v1. */
        val DEFAULT = MigrationRegistry()
    }
}
