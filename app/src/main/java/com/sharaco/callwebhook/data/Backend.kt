package com.sharaco.callwebhook.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.sharaco.callwebhook.domain.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await

@Singleton
class Backend
@Inject
constructor(
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
    private val functions: FirebaseFunctions,
    private val spark: SparkWorkspace,
    private val sparkSync: SparkSync,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun call(name: String, data: Row = emptyMap()): Row =
        try {
            when (name) {
                "registerDevice" -> sparkSync.register(data)
                "deviceHeartbeat" -> sparkSync.heartbeat(data)
                "ingestCalls" -> sparkSync.ingest(data)
                "createCompany" -> spark.createCompany(data)
                "saveEmployee" -> spark.saveEmployee(data)
                "createInvite" -> spark.createInvite(data)
                "inspectInvite" -> spark.inspectInvite(data.str("token"))
                "joinCompany" -> spark.join(data.str("token"))
                "revokeInvite" -> spark.revoke(data)
                "approveJoin" -> {
                    spark.approve(data.str("companyId"), data.str("uid"))
                    emptyMap()
                }
                "getReport" -> spark.report(data)
                "listCalls" -> mapOf("calls" to spark.calls(data), "hasMore" to false)
                else ->
                    functions
                        .getHttpsCallable(name)
                        .apply { setTimeout(130, TimeUnit.SECONDS) }
                        .call(data)
                        .await()
                        .data as? Row ?: emptyMap()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("CallTrackBackend", "$name failed: ${backendErrorCode(e)}")
            throw BackendOperationException(name, e)
        }

    suspend fun session(): Session? {
        return spark.session()
    }

    fun watchCallChanges(
        session: Session,
        filters: Row,
        changed: () -> Unit,
        failed: (Exception) -> Unit,
    ): ListenerRegistration {
        var query = firestore.collection("companies/${session.companyId}/call_logs").limit(5001)
        if (!session.manager) query = query.whereEqualTo("uid", session.uid)
        if (filters.num("from") > 0)
            query = query.whereGreaterThanOrEqualTo("startedAt", filters.num("from"))
        if (filters.num("to") > 0) query = query.whereLessThan("startedAt", filters.num("to"))
        return query.addSnapshotListener { snapshot, error ->
            if (error != null) failed(error)
            else if (snapshot != null) {
                // Includes the initial snapshot to cover writes racing the initial report fetch.
                // Metadata-only events are excluded by the default listener options.
                changed()
            }
        }
    }

    fun watch(
        session: Session,
        collection: String,
        callback: (List<Row>, Exception?) -> Unit,
    ): ListenerRegistration {
        var query = firestore.collection("companies/${session.companyId}/$collection").limit(1000)
        if (!session.manager && collection == "devices")
            query = query.whereEqualTo("uid", session.uid)
        if (collection in listOf("audit", "notifications"))
            query =
                query
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(100)
        return query.addSnapshotListener { value, error ->
            callback(
                value
                    ?.documents
                    ?.map {
                        if (collection == "devices") deviceRow(it.id, it.data.orEmpty())
                        else it.data.orEmpty() + ("id" to it.id)
                    }
                    .orEmpty(),
                error,
            )
        }
    }
}

class BackendOperationException(val operation: String, cause: Throwable) : Exception(cause)

fun Throwable.backendCause(): Throwable =
    if (this is BackendOperationException) cause?.backendCause() ?: this else this

fun backendErrorCode(error: Throwable): String =
    when (error) {
        is BackendOperationException -> backendErrorCode(error.cause ?: error)
        is FirebaseFirestoreException -> "FIRESTORE_${error.code.name}"
        is FirebaseFunctionsException -> "FUNCTIONS_${error.code.name}"
        else -> error.javaClass.simpleName
    }

fun humanError(error: Throwable): String =
    when (error) {
        is BackendOperationException ->
            "${error.operation}: ${humanError(error.cause ?: Exception())} [${backendErrorCode(error)}]"
        is FirebaseFirestoreException ->
            "[FIRESTORE_${error.code.name}] " +
                when (error.code) {
                    FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                        "Your account is signed in, but workspace access was denied. Ask your administrator to check your membership, deployed Firestore rules and App Check configuration."
                    FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                        "Your session could not be verified. Sign out and sign in again."
                    FirebaseFirestoreException.Code.UNAVAILABLE,
                    FirebaseFirestoreException.Code.DEADLINE_EXCEEDED ->
                        "Your workspace is temporarily unreachable. Check your connection and retry."
                    else ->
                        "Workspace request failed (${error.code}). Retry or share this code with your administrator."
                }
        is FirebaseFunctionsException ->
            "[FUNCTIONS_${error.code.name}] " +
                when (error.code) {
                    FirebaseFunctionsException.Code.UNAVAILABLE,
                    FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                        "Unable to connect. Pending calls are saved on this device; retry when online."
                    FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                        "Your session or device could not be verified. Sign in again; if it persists, ask your administrator to check App Check for this APK."
                    FirebaseFunctionsException.Code.NOT_FOUND ->
                        "Callable endpoint was not found in sharaco-call-logger / europe-west1. This operation still requires a deployed backend."
                    else ->
                        error.message?.substringBefore("\n")
                            ?: "Unable to complete this action. Please retry."
                }
        is java.io.IOException -> "Unable to connect. Check your internet connection."
        is IllegalStateException,
        is IllegalArgumentException -> error.message ?: "Please check the information and retry."
        else -> "Unable to complete this action. Check your connection and try again."
    }
