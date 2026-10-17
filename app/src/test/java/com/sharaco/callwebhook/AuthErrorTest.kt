package com.sharaco.callwebhook

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.sharaco.callwebhook.data.BackendOperationException
import com.sharaco.callwebhook.data.backendCause
import com.sharaco.callwebhook.data.signInError
import org.junit.Assert.*
import org.junit.Test

class AuthErrorTest {
    @Test
    fun operationDiagnosticsPreserveUnderlyingPermissionFailureForSync() {
        val original = IllegalStateException("permission failure")
        val wrapped =
            BackendOperationException("heartbeat", BackendOperationException("call", original))
        assertSame(original, wrapped.backendCause())
        assertSame(original, original.backendCause())
    }

    @Test
    fun providerCancellationIsVisibleAndOffersExplicitRecovery() {
        val message =
            signInError(GetCredentialCancellationException("Activity is cancelled by the user"))
        assertTrue(message.contains("GOOGLE_CANCELLED"))
        assertTrue(message.contains("other account selector"))
    }

    @Test
    fun noCredentialDoesNotAssumeTheUserHasNoGoogleAccount() {
        val message = signInError(NoCredentialException("No credentials"))
        assertTrue(message.contains("other account selector"))
        assertFalse(message.contains("No Google account is available"))
    }

    @Test
    fun unexpectedFailureUsesSafeCodeInsteadOfProviderPayload() {
        val message = signInError(IllegalStateException("private-provider-payload"))
        assertTrue(message.contains("AUTH_IllegalStateException"))
        assertFalse(message.contains("private-provider-payload"))
    }
}
