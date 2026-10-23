package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BinRetentionTest : RepositoryTestBase() {
    private val settings = MapSettings()
    private val binSettings = BinSettings(settings)

    @Test
    fun retention_defaultsTo30Days_andRoundTrips() =
        runTest {
            assertThat(binSettings.retention.first()).isEqualTo(BinRetention.DAYS_30)

            binSettings.set(BinRetention.DAYS_7).orThrow()
            assertThat(binSettings.retention.first()).isEqualTo(BinRetention.DAYS_7)

            settings.write(BinSettings.KEY, "garbage")
            assertThat(binSettings.retention.first()).isEqualTo(BinRetention.DAYS_30)
        }

    @Test
    fun purgeExpired_usesTheStoredRetention() =
        runTest {
            val purger = BinPurger(documents, binSettings)
            documents.delete(documents.create(spec("Ten days")).orThrow().path).orThrow()
            clock.advanceMs(10 * DAY_MS)
            documents.delete(documents.create(spec("Fresh")).orThrow().path).orThrow()

            assertThat(purger.purgeExpired().orThrow()).isEqualTo(0) // 30 days: nothing is old enough
            binSettings.set(BinRetention.DAYS_7).orThrow()
            assertThat(purger.purgeExpired().orThrow()).isEqualTo(1)
            assertThat(documents.bin.value.map { it.name }).containsExactly("Fresh.folio")

            clock.advanceMs(1000 * DAY_MS)
            binSettings.set(BinRetention.NEVER).orThrow()
            assertThat(purger.purgeExpired().orThrow()).isEqualTo(0)
            assertThat(documents.bin.value).hasSize(1)
        }

    @Test
    fun purgeExpired_keepsEntryOnTheRetentionBoundaryAndDeletesItAfter() =
        runTest {
            val purger = BinPurger(documents, binSettings)
            documents.delete(documents.create(spec("Edge")).orThrow().path).orThrow()

            clock.advanceMs(30 * DAY_MS)
            assertThat(purger.purgeExpired().orThrow()).isEqualTo(0)
            clock.advanceMs(1)
            assertThat(purger.purgeExpired().orThrow()).isEqualTo(1)
        }

    @Test
    fun daysLeft_roundsUpAndStopsAtZero() {
        val entry = BinEntry(".trash/1__A.folio", "A.folio", deletedMs = 1_000, docId = null, isFolder = false)
        val start = 1_000L

        assertThat(daysLeft(entry, BinRetention.DAYS_30, start)).isEqualTo(30)
        assertThat(daysLeft(entry, BinRetention.DAYS_30, start + 1)).isEqualTo(30)
        assertThat(daysLeft(entry, BinRetention.DAYS_30, start + 29 * DAY_MS + 1)).isEqualTo(1)
        assertThat(daysLeft(entry, BinRetention.DAYS_30, start + 30 * DAY_MS)).isEqualTo(0)
        assertThat(daysLeft(entry, BinRetention.DAYS_7, start + 40 * DAY_MS)).isEqualTo(0)
        assertThat(daysLeft(entry, BinRetention.NEVER, start + 40 * DAY_MS)).isNull()
    }

    @Test
    fun daysSinceDeleted_countsWholeDays() {
        val entry = BinEntry(".trash/1__A.folio", "A.folio", deletedMs = 5_000, docId = null, isFolder = false)

        assertThat(daysSinceDeleted(entry, 5_000)).isEqualTo(0)
        assertThat(daysSinceDeleted(entry, 5_000 + DAY_MS - 1)).isEqualTo(0)
        assertThat(daysSinceDeleted(entry, 5_000 + 3 * DAY_MS)).isEqualTo(3)
        assertThat(daysSinceDeleted(entry, 0)).isEqualTo(0) // clock moved back
    }

    private class MapSettings : SettingsStore {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun read(key: String): Flow<String?> = values.map { it[key] }

        override suspend fun write(
            key: String,
            value: String,
        ): Outcome<Unit> {
            values.value = values.value + (key to value)
            return Outcome.Success(Unit)
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
