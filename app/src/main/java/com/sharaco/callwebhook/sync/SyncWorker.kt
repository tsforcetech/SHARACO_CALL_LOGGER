package com.sharaco.callwebhook.sync

import android.content.Context
import android.provider.CallLog
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
        val success = engine.sync()
        if (engine.enrolled()) SyncWork.observe(applicationContext)
        return if (success) Result.success()
        else if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}

/** JobScheduler watches the provider even when no activity or app process is running. */
class CallHistoryWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine =
            EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
                .engine()
        if (!engine.enrolled()) return Result.success()
        // Re-arm the next one-shot observer; regular work owns upload retries/offline queues.
        SyncWork.observe(applicationContext, rearm = true)
        SyncWork.now(applicationContext)
        return Result.success()
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
        observe(context)
        now(context)
    }

    internal fun observationRequest() =
        OneTimeWorkRequestBuilder<CallHistoryWorker>()
            .addTag(TAG)
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(2, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                    .build()
            )
            .build()

    fun observe(context: Context, rearm: Boolean = false) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "$TAG-history",
                if (rearm) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                observationRequest(),
            )
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
