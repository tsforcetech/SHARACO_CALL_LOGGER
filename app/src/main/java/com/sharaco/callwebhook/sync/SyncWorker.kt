package com.sharaco.callwebhook.sync

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncEntryPoint {
    fun engine(): SyncEngine
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine =
            EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
                .engine()
        return if (engine.sync()) Result.success()
        else if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}

object SyncWork {
    private const val TAG = "calltrack-sync"

    fun schedule(context: Context) {
        val periodic =
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, periodic)
        now(context)
    }

    fun now(context: Context) {
        // No network constraint here: new records must be collected locally while offline.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "$TAG-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .addTag(TAG)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "$TAG-online",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .addTag(TAG)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
}
