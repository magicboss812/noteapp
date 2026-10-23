package dev.folio.core.storage.work

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.storage.repo.BinPurger
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Start-up bin upkeep: queues the daily purge job and purges expired entries right away. */
interface BinMaintenance {
    /** Call once the library folder is usable. */
    suspend fun runAtStart()
}

@Singleton
internal class WorkBinMaintenance
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val purger: BinPurger,
        private val dispatchers: FolioDispatchers,
    ) : BinMaintenance {
        override suspend fun runAtStart() {
            withContext(dispatchers.io) { BinPurgeWorker.schedule(context) }
            purger.purgeExpired()
        }
    }
