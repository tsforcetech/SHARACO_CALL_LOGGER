package com.sharaco.callwebhook.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.sharaco.callwebhook.BuildConfig
import com.sharaco.callwebhook.data.*
import com.sharaco.callwebhook.domain.*
import com.sharaco.callwebhook.sync.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await

data class AppState(
    val session: Session? = null,
    val loading: Boolean = true,
    val busy: Int = 0,
    val error: String = "",
    val authError: String = "",
    val workspaceError: String = "",
    val authenticatedEmail: String = "",
    val theme: String = "SYSTEM",
    val filters: Filters = Filters(),
    val report: Row = emptyMap(),
    val calls: List<Row> = emptyList(),
    val cursor: List<*>? = null,
    val hasMore: Boolean = false,
    val sort: String = "NEWEST",
    val employees: List<Row> = emptyList(),
    val devices: List<Row> = emptyList(),
    val audit: List<Row> = emptyList(),
    val notifications: List<Row> = emptyList(),
    val local: List<LocalCall> = emptyList(),
    val localLimit: Int = 100,
    val pending: Int = 0,
    val invite: Row = emptyMap(),
    val preview: Row = emptyMap(),
    val permission: Boolean = false,
    val notificationPermission: Boolean = false,
    val syncPhase: String = "Ready",
    val lastSuccess: Long = 0,
    val syncError: String = "",
    val installation: String = "",
)

@HiltViewModel
class AppViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val backend: Backend,
    private val google: GoogleAuth,
    private val prefs: Preferences,
    private val dao: CallDao,
    private val engine: SyncEngine,
    private val reader: CallLogReader,
) : ViewModel() {
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private val listeners = mutableListOf<ListenerRegistration>()
    private var localJob: Job? = null
    private var pendingJob: Job? = null
    private var reportJob: Job? = null
    private var callsJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.themeFlow.collect { theme -> mutable.update { it.copy(theme = theme) } }
        }
        viewModelScope.launch {
            prefs.statusFlow.collect { value ->
                mutable.update { it.copy(lastSuccess = value.first, syncError = value.second) }
            }
        }
        viewModelScope.launch {
            engine.phase.collect { phase -> mutable.update { it.copy(syncPhase = phase) } }
        }
        reload()
    }

    private fun task(block: suspend () -> Unit): Job = viewModelScope.launch {
        mutable.update { it.copy(busy = it.busy + 1) }
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(error = humanError(e)) }
        } finally {
            mutable.update { it.copy(busy = (it.busy - 1).coerceAtLeast(0), loading = false) }
        }
    }

    fun message(value: String) {
        mutable.update { it.copy(error = value) }
    }

    fun clearMessage() {
        message("")
    }

    fun signIn(activity: Activity) = task {
        mutable.update { it.copy(authError = "", workspaceError = "") }
        try {
            google.signIn(activity)
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            return@task
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            mutable.update { it.copy(authError = signInError(e)) }
            return@task
        }
        loadSession()
    }

    fun reload() = task { loadSession() }

    private suspend fun loadSession() {
        listeners.forEach { it.remove() }
        listeners.clear()
        localJob?.cancel()
        pendingJob?.cancel()
        reportJob?.cancel()
        callsJob?.cancel()
        mutable.update {
            it.copy(
                workspaceError = "",
                authenticatedEmail = backend.auth.currentUser?.email.orEmpty(),
            )
        }
        val s =
            try {
                withTimeout(25000) { backend.session() }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val explanation =
                    if (e is TimeoutCancellationException)
                        "Loading your workspace timed out. Check your connection and retry."
                    else humanError(e)
                mutable.update {
                    it.copy(session = null, workspaceError = explanation, loading = false)
                }
                return
            }
        val config = prefs.config(s?.uid)
        val today = LocalDate.now(ZoneId.of(s?.zone ?: "UTC"))
        mutable.update {
            it.copy(
                session = s,
                installation = config.installation,
                local = emptyList(),
                pending = 0,
                audit = emptyList(),
                notifications = emptyList(),
                filters = Filters(today),
                report = emptyMap(),
                calls = emptyList(),
                employees = emptyList(),
                devices = emptyList(),
                loading = false,
            )
        }
        permissionsChanged()
        if (s == null || s.companyId.isBlank()) return
        watchLocal(s)
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            dao.observePending(s.scope).collect { count ->
                mutable.update { it.copy(pending = count) }
            }
        }
        val collections = buildList {
            add("devices")
            if (s.manager) {
                add("employees")
                add("notifications")
            }
            if (s.role == "OWNER") add("audit")
        }
        collections.forEach { collection ->
            listeners +=
                backend.watch(s, collection) { rows, error ->
                    if (error != null) message(humanError(error))
                    else
                        mutable.update {
                            when (collection) {
                                "devices" -> it.copy(devices = rows)
                                "employees" -> it.copy(employees = rows)
                                "audit" -> it.copy(audit = rows)
                                else -> it.copy(notifications = rows)
                            }
                        }
                }
        }
        listeners +=
            backend.firestore
                .document("companies/${s.companyId}/members/${s.uid}")
                .addSnapshotListener { snap, error ->
                    if (error != null) message(humanError(error))
                    else if (snap != null) {
                        val current = snap.data.orEmpty()
                        if (current.str("status") != "ACTIVE") {
                            task {
                                SyncWork.cancel(context)
                                reportJob?.cancel()
                                callsJob?.cancel()
                                listeners.forEach { it.remove() }
                                listeners.clear()
                                engine.reset()
                                mutable.update {
                                    AppState(
                                        session = Session(s.uid),
                                        loading = false,
                                        error =
                                            "Your membership is inactive. Contact your company owner.",
                                        theme = it.theme,
                                    )
                                }
                            }
                        } else if (current.str("role") != mutable.value.session?.role) reload()
                        else
                            mutable.update { it.copy(session = it.session?.copy(member = current)) }
                    }
                }
        refreshReport()
        loadCalls()
        if (config.company == s.companyId && config.uid == s.uid) SyncWork.schedule(context)
        viewModelScope.launch {
            try {
                FirebaseMessaging.getInstance().register().await()
                backend.call(
                    "saveNotificationToken",
                    mapOf("fid" to FirebaseInstallations.getInstance().id.await()),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.w("CallTrack", "Notification token registration unavailable")
            }
        }
    }

    private fun watchLocal(s: Session) {
        localJob?.cancel()
        localJob = viewModelScope.launch {
            dao.observe(s.scope, mutable.value.localLimit).collect { rows ->
                mutable.update { it.copy(local = rows) }
            }
        }
    }

    fun moreLocal() {
        mutable.update { it.copy(localLimit = it.localLimit + 100) }
        mutable.value.session?.let(::watchLocal)
    }

    fun permissionsChanged() {
        mutable.update {
            it.copy(
                permission = reader.permitted(),
                notificationPermission = engine.notifications(),
            )
        }
    }

    fun theme(value: String) = task { prefs.edit { it[Preferences.theme] = value } }

    fun setFilters(value: Filters) {
        mutable.update { it.copy(filters = value, report = emptyMap(), calls = emptyList()) }
        refreshReport()
        loadCalls()
    }

    fun setSort(value: String) {
        mutable.update { it.copy(sort = value) }
        loadCalls()
    }

    fun refreshReport() {
        val s = mutable.value.session ?: return
        if (s.companyId.isBlank()) return
        reportJob?.cancel()
        val filters = mutable.value.filters
        reportJob = task {
            val report =
                backend.call(
                    "getReport",
                    mapOf("companyId" to s.companyId, "filters" to filters.payload(s.zone)),
                )
            if (mutable.value.filters == filters) mutable.update { it.copy(report = report) }
        }
    }

    fun loadCalls(more: Boolean = false) {
        val s = mutable.value.session ?: return
        if (s.companyId.isBlank()) return
        callsJob?.cancel()
        val snapshot = mutable.value
        callsJob = task {
            val response =
                backend.call(
                    "listCalls",
                    mapOf(
                        "companyId" to s.companyId,
                        "filters" to snapshot.filters.payload(s.zone),
                        "sort" to snapshot.sort,
                        "cursor" to if (more) snapshot.cursor else null,
                    ),
                )
            mutable.update {
                it.copy(
                    calls =
                        ((if (more) it.calls else emptyList()) + response.rows("calls"))
                            .distinctBy { row -> row.str("id") },
                    cursor = response["cursor"] as? List<*>,
                    hasMore = response.flag("hasMore"),
                )
            }
        }
    }

    fun createCompany(values: Row, done: () -> Unit = {}) = task {
        backend.call("createCompany", values)
        loadSession()
        done()
    }

    fun inspectInvite(token: String) = task {
        val p = backend.call("inspectInvite", mapOf("token" to token))
        mutable.update { it.copy(preview = p + ("token" to token)) }
    }

    fun join(token: String) = task {
        backend.call("joinCompany", mapOf("token" to token, "consent" to true))
        loadSession()
    }

    fun clearPreview() {
        mutable.update { it.copy(preview = emptyMap()) }
    }

    fun saveEmployee(values: Row, done: () -> Unit) = task {
        val s = mutable.value.session ?: return@task
        val result = backend.call("saveEmployee", values + ("companyId" to s.companyId))
        if (values.str("employeeId").isEmpty()) {
            val invite =
                backend.call(
                    "createInvite",
                    mapOf("companyId" to s.companyId, "employeeId" to result.str("employeeId")),
                )
            mutable.update {
                it.copy(invite = invite + values + ("employeeId" to result.str("employeeId")))
            }
        }
        done()
    }

    fun invite(employee: Row, days: Int) = task {
        val s = mutable.value.session ?: return@task
        val result =
            backend.call(
                "createInvite",
                mapOf(
                    "companyId" to s.companyId,
                    "employeeId" to employee.str("id"),
                    "days" to days,
                ),
            )
        mutable.update { it.copy(invite = result + employee) }
    }

    fun clearInvite() {
        mutable.update { it.copy(invite = emptyMap()) }
    }

    fun action(name: String, values: Row = emptyMap(), done: () -> Unit = {}) = task {
        val s = mutable.value.session ?: return@task
        backend.call(name, values + ("companyId" to s.companyId))
        done()
        if (name == "updateCompany") loadSession()
    }

    fun register(replace: Boolean) = task {
        val s = mutable.value.session ?: return@task
        val config = prefs.config(s.uid)
        check(reader.permitted()) { "Enable call history permission first." }
        backend.call(
            "registerDevice",
            mapOf(
                "companyId" to s.companyId,
                "deviceId" to config.installation,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "androidVersion" to Build.VERSION.RELEASE,
                "appVersion" to BuildConfig.VERSION_NAME,
                "callLogPermission" to reader.permitted(),
                "notificationPermission" to engine.notifications(),
                "replace" to replace,
            ),
        )
        if (config.company != s.companyId || config.uid != s.uid) engine.reset()
        prefs.edit {
            it[Preferences.company] = s.companyId
            it[Preferences.uid] = s.uid
            it[Preferences.initial] = s.member.num("initialSyncFrom")
            it[Preferences.retention] = s.company.num("retentionDays")
        }
        SyncWork.schedule(context)
        message("Device registered. Synchronization is scheduled.")
    }

    fun syncNow() = task {
        engine.sync()
        refreshReport()
        loadCalls()
    }

    fun signOut() = task {
        SyncWork.cancel(context)
        engine.reset()
        listeners.forEach { it.remove() }
        listeners.clear()
        localJob?.cancel()
        pendingJob?.cancel()
        reportJob?.cancel()
        callsJob?.cancel()
        try {
            google.signOut(context)
        } finally {
            mutable.update { AppState(loading = false, theme = it.theme) }
        }
    }

    fun leave() = task {
        val s = mutable.value.session ?: return@task
        backend.call("leaveCompany", mapOf("companyId" to s.companyId, "confirm" to "LEAVE"))
        SyncWork.cancel(context)
        engine.reset()
        loadSession()
    }

    fun delete(activity: Activity, company: Boolean) = task {
        google.signIn(activity, true)
        backend.call(
            "requestDeletion",
            mapOf("confirm" to "DELETE") +
                if (company) mapOf("companyId" to mutable.value.session?.companyId) else emptyMap(),
        )
        SyncWork.cancel(context)
        engine.reset()
        google.signOut(context)
        listeners.forEach { it.remove() }
        listeners.clear()
        mutable.update { AppState(loading = false, theme = it.theme) }
    }

    override fun onCleared() {
        listeners.forEach { it.remove() }
        super.onCleared()
    }
}
