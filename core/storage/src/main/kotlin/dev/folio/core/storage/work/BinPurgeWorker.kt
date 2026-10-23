package dev.folio.core.storage.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.BinPurger
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface BinPurgeEntryPoint {
    fun purger(): BinPurger
}

/** Daily job: deletes bin entries older than the retention setting (09-storage-library.md#trash). */
class BinPurgeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val purger = EntryPointAccessors.fromApplication(applicationContext, BinPurgeEntryPoint::class.java).purger()
        return when (val result = purger.purgeExpired()) {
            is Outcome.Success -> {
                if (result.value > 0) FolioLog.i(TAG, "purged ${result.value} bin entries")
                Result.success()
            }

            // The library folder may be unavailable (access revoked); try again at the next period.
            is Outcome.Failure -> {
                FolioLog.w(TAG, "bin purge failed: ${result.message}", result.cause)
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "BinPurgeWorker"
        private const val WORK_NAME = "bin-purge"

        /** Queues the daily purge once; an already queued job keeps its schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BinPurgeWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
