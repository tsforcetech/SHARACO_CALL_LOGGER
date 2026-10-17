package com.sharaco.callwebhook.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.functions.FirebaseFunctionsException
import com.sharaco.callwebhook.BuildConfig
import com.sharaco.callwebhook.data.*
import com.sharaco.callwebhook.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class SyncEngine
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val backend: Backend,
    private val prefs: Preferences,
    private val dao: CallDao,
    private val reader: CallLogReader,
) {
    private val mutex = Mutex()
    private val phaseState = MutableStateFlow("Ready")
    val phase = phaseState.asStateFlow()

    fun notifications() =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun reset() = mutex.withLock {
        dao.clear()
        prefs.clearSession()
        phaseState.value = "Ready"
    }

    suspend fun sync(): Boolean = mutex.withLock {
        var config = prefs.config()
        if (config.company.isBlank() || config.uid != backend.auth.currentUser?.uid)
            return@withLock true
        suspend fun heartbeat(success: Boolean, error: String = ""): Row =
            backend.call(
                "deviceHeartbeat",
                mapOf(
                    "companyId" to config.company,
                    "deviceId" to config.installation,
                    "callLogPermission" to reader.permitted(),
                    "notificationPermission" to notifications(),
                    "pendingRecordCount" to dao.pendingCount(config.scope),
                    "success" to success,
                    "error" to error,
                    "phase" to phaseState.value,
                    "appVersion" to BuildConfig.VERSION_NAME,
                ),
            )
        try {
            phaseState.value = "Checking device…"
            // Refresh retention before uploading, but permit offline collection if the network
            // fails.
            try {
                val policy = heartbeat(false)
                prefs.edit {
                    it[Preferences.retention] = policy.num("retentionDays")
                    it[Preferences.initial] = policy.num("initialSyncFrom")
                }
                config = prefs.config()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (
                    e is FirebaseFunctionsException &&
                        e.code in
                            listOf(
                                FirebaseFunctionsException.Code.PERMISSION_DENIED,
                                FirebaseFunctionsException.Code.UNAUTHENTICATED,
                            )
                )
                    throw e
            }
            phaseState.value = "Checking call history…"
            val now = System.currentTimeMillis()
            val cutoff = maxOf(config.initial, now - config.retention * 86400000)
            dao.prune(config.scope, cutoff)
            val full = now - config.fullScan > 7 * 86400000L
            val from = if (full) cutoff else maxOf(cutoff, config.marker - 48 * 3600000L)
            val marker = reader.scan(config, from)
            // Marker moves only after each scanned record is durable in Room.
            prefs.edit {
                it[Preferences.marker] = marker
                if (full) it[Preferences.fullScan] = now
            }
            while (true) {
                check(backend.auth.currentUser?.uid == config.uid) { "Please sign in again." }
                val batch = dao.pending(config.scope)
                if (batch.isEmpty()) break
                phaseState.value = "Uploading ${dao.pendingCount(config.scope)} pending calls…"
                val result =
                    backend.call(
                        "ingestCalls",
                        mapOf(
                            "companyId" to config.company,
                            "deviceId" to config.installation,
                            "calls" to batch.map { it.payload() },
                        ),
                    )
                val confirmed =
                    (result["confirmedIds"] as? List<*>)
                        ?.filterIsInstance<String>()
                        ?.toSet()
                        .orEmpty()
                check(confirmed.containsAll(batch.map { it.id })) {
                    "Server did not confirm all records. They will be retried."
                }
                batch.forEach { dao.confirm(it.id, it.seconds, it.type) }
            }
            heartbeat(true)
            prefs.edit {
                it[Preferences.success] = System.currentTimeMillis()
                it[Preferences.error] = ""
            }
            phaseState.value = "Synchronization complete"
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = humanError(e)
            dao.failure(config.scope, message)
            prefs.edit { it[Preferences.error] = message }
            phaseState.value = message
            if (
                e is FirebaseFunctionsException &&
                    e.code == FirebaseFunctionsException.Code.PERMISSION_DENIED
            ) {
                dao.clear()
                prefs.clearSession()
                SyncWork.cancel(context)
            } else {
                try {
                    heartbeat(false, message)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                }
            }
            false
        }
    }
}
