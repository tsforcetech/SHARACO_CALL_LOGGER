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
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun call(name: String, data: Row = emptyMap()): Row =
        functions
            .getHttpsCallable(name)
            .apply { setTimeout(130, TimeUnit.SECONDS) }
            .call(data)
            .await()
            .data as? Row ?: emptyMap()

    suspend fun session(): Session? {
        val user = auth.currentUser ?: return null
        val cid =
            firestore.document("users/${user.uid}").get().await().data?.get("companyId") as? String
                ?: ""
        if (cid.isBlank()) return Session(user.uid)
        val member =
            firestore.document("companies/$cid/members/${user.uid}").get().await().data.orEmpty()
        check(member.str("status") == "ACTIVE") {
            "Your company access is inactive. Contact your company owner."
        }
        val company = firestore.document("companies/$cid").get().await().data.orEmpty()
        check(company.str("status") == "ACTIVE") { "Your company is unavailable." }
        return Session(user.uid, cid, company, member)
    }

    fun watch(
        session: Session,
        collection: String,
        callback: (List<Row>, Exception?) -> Unit,
    ): ListenerRegistration {
        var query = firestore.collection("companies/${session.companyId}/$collection").limit(1000)
        if (!session.manager && collection == "devices")
            query = query.whereEqualTo("employeeUid", session.uid)
        if (collection in listOf("audit", "notifications"))
            query =
                query
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(100)
        return query.addSnapshotListener { value, error ->
            callback(value?.documents?.map { it.data.orEmpty() + ("id" to it.id) }.orEmpty(), error)
        }
    }
}

fun humanError(error: Throwable): String =
    when (error) {
        is FirebaseFirestoreException ->
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
            when (error.code) {
                FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Unable to connect. Pending calls are saved on this device; retry when online."
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Your session or device could not be verified. Sign in again; if it persists, ask your administrator to check App Check for this APK."
                FirebaseFunctionsException.Code.NOT_FOUND ->
                    "This feature is not available on the server yet. Ask your administrator to deploy the CallTrack backend to the configured Firebase project."
                else ->
                    error.message?.substringBefore("\n")
                        ?: "Unable to complete this action. Please retry."
            }
        is java.io.IOException -> "Unable to connect. Check your internet connection."
        is IllegalStateException,
        is IllegalArgumentException -> error.message ?: "Please check the information and retry."
        else -> "Unable to complete this action. Check your connection and try again."
    }
