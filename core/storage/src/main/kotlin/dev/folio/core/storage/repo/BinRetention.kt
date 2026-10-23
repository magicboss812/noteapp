package dev.folio.core.storage.repo

import dev.folio.core.common.Outcome
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val DAY_MS = 24L * 60 * 60 * 1000

/** How long deleted items stay in the bin (09-storage-library.md#trash). */
enum class BinRetention(
    /** Days an entry is kept; null = forever. */
    val days: Int?,
    internal val key: String,
) {
    DAYS_7(7, "7"),
    DAYS_30(30, "30"),
    DAYS_90(90, "90"),
    NEVER(null, "never"),
    ;

    companion object {
        val Default = DAYS_30

        internal fun of(key: String?): BinRetention = entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * Whole days left before [entry] is purged under [retention] (rounded up, 0 on the last day), null when it is kept
 * forever.
 */
fun daysLeft(
    entry: BinEntry,
    retention: BinRetention,
    nowMs: Long,
): Int? {
    val days = retention.days ?: return null
    val leftMs = entry.deletedMs + days * DAY_MS - nowMs
    return if (leftMs <= 0) 0 else ((leftMs + DAY_MS - 1) / DAY_MS).toInt()
}

/** Whole days since [entry] was deleted (0 = today). */
fun daysSinceDeleted(
    entry: BinEntry,
    nowMs: Long,
): Int = ((nowMs - entry.deletedMs).coerceAtLeast(0) / DAY_MS).toInt()

/** The bin retention choice, stored in the app settings. */
@Singleton
class BinSettings
    @Inject
    constructor(
        private val settings: SettingsStore,
    ) {
        /** The chosen retention; [BinRetention.Default] while unset. */
        val retention: Flow<BinRetention> = settings.read(KEY).map(BinRetention::of)

        suspend fun set(retention: BinRetention): Outcome<Unit> = settings.write(KEY, retention.key)

        internal companion object {
            const val KEY = "bin.retention"
        }
    }

/** Deletes bin entries older than the retention setting; run at library start and by the daily job. */
@Singleton
class BinPurger
    @Inject
    constructor(
        private val documents: DocumentRepository,
        private val binSettings: BinSettings,
    ) {
        /** Returns the number of entries deleted. */
        suspend fun purgeExpired(): Outcome<Int> = documents.purgeBin(binSettings.retention.first().days)
    }
