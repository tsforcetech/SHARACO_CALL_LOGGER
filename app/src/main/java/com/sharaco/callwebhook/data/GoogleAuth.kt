package com.sharaco.callwebhook.data

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.*
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.sharaco.callwebhook.R
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

class GoogleAuth @Inject constructor(private val auth: FirebaseAuth) {
    suspend fun signIn(activity: Activity, reauthenticate: Boolean = false) {
        val option =
            GetSignInWithGoogleOption.Builder(activity.getString(R.string.default_web_client_id))
                .build()
        val response =
            CredentialManager.create(activity)
                .getCredential(activity, GetCredentialRequest(listOf(option)))
        val result = response.credential
        require(
            result is CustomCredential &&
                (result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                    result.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL)
        ) {
            "Google returned an unsupported credential. Update Google Play services and retry."
        }
        val token = GoogleIdTokenCredential.createFrom(result.data).idToken
        val credential = GoogleAuthProvider.getCredential(token, null)
        if (reauthenticate)
            checkNotNull(auth.currentUser) { "Please sign in again before continuing." }
                .reauthenticate(credential)
                .await()
        else auth.signInWithCredential(credential).await()
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}

/** User-safe diagnostics. Never include ID tokens or the provider's raw error payload. */
fun signInError(error: Throwable): String =
    when (error) {
        is NoCredentialException ->
            "No Google account is available. Add a Google account in Android Settings, then retry."
        is GetCredentialProviderConfigurationException ->
            "Google sign-in is unavailable on this device. Enable or update Google Play services and retry."
        is GetCredentialUnsupportedException ->
            "This device does not support Google sign-in. Use an Android device with Google Play services."
        is FirebaseNetworkException ->
            "Google authentication could not reach Firebase. Check your internet connection and retry."
        is FirebaseAuthException ->
            when (error.errorCode) {
                "ERROR_OPERATION_NOT_ALLOWED" ->
                    "Google sign-in is not enabled for this app. Ask your administrator to enable the Google provider in Firebase Authentication."
                "ERROR_INVALID_CREDENTIAL",
                "ERROR_INVALID_CUSTOM_TOKEN",
                "ERROR_CUSTOM_TOKEN_MISMATCH" ->
                    "Google could not verify this app. Ask your administrator to check the release signing fingerprint and OAuth configuration."
                "ERROR_APP_NOT_AUTHORIZED",
                "ERROR_INVALID_API_KEY" ->
                    "This app is not authorized by Firebase. Ask your administrator to check the Android app registration and API configuration."
                "ERROR_USER_DISABLED" ->
                    "This account is disabled. Contact your company administrator."
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" ->
                    "This email already uses a different sign-in method. Contact your administrator to link your Google account."
                else ->
                    "Firebase could not sign you in (${error.errorCode}). Retry or share this code with your administrator."
            }
        is GetCredentialException ->
            "Google sign-in could not finish (${error.type.substringAfterLast('.')}). Retry; if it continues, ask your administrator to check this APK's signing fingerprint and Google sign-in setup."
        else ->
            "Sign-in could not finish. Retry, or contact your administrator with code AUTH_${error.javaClass.simpleName}."
    }
