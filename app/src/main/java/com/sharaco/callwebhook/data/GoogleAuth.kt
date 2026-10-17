package com.sharaco.callwebhook.data

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.sharaco.callwebhook.R
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class GoogleAuth @Inject constructor(private val auth: FirebaseAuth) {
    suspend fun signIn(activity: Activity, reauthenticate: Boolean = false) {
        val option = GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id)).build()
        val response = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(option)))
        val token = GoogleIdTokenCredential.createFrom(response.credential.data).idToken
        val credential = GoogleAuthProvider.getCredential(token, null)
        if (reauthenticate) auth.currentUser?.reauthenticate(credential)?.await() else auth.signInWithCredential(credential).await()
    }
    suspend fun signOut(context: Context) {
        auth.signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
