package com.clickbeans.wifidatabase.ui.auth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clickbeans.wifidatabase.data.api.ApiResult
import com.clickbeans.wifidatabase.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthEvent {
    data object LoggedIn : AuthEvent
    data object Registered : AuthEvent
    data object ResetEmailSent : AuthEvent
    data object PasswordChanged : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var lastEmail by mutableStateOf<String?>(null)
        private set
    var resendRequested by mutableStateOf(false)
        private set

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events: Flow<AuthEvent> = _events.receiveAsFlow()

    fun login(email: String, password: String) {
        lastEmail = email
        runAuth(AuthEvent.LoggedIn) { authRepository.login(email, password) }
    }

    fun register(email: String, password: String) {
        lastEmail = email
        runAuth(AuthEvent.Registered) { authRepository.register(email, password) }
    }

    fun resendVerification(email: String) {
        lastEmail = email
        resendRequested = false
        runAuth(null) {
            val result = authRepository.resendVerification(email)
            if (result is ApiResult.Success) resendRequested = true
            result
        }
    }

    fun requestPasswordReset(email: String) {
        lastEmail = email
        runAuth(AuthEvent.ResetEmailSent) { authRepository.requestPasswordReset(email) }
    }

    fun confirmPasswordReset(token: String, newPassword: String) {
        runAuth(AuthEvent.PasswordChanged) { authRepository.confirmPasswordReset(token, newPassword) }
    }

    private fun runAuth(successEvent: AuthEvent?, block: suspend () -> ApiResult<Unit>) {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            val result = block()
            when (result) {
                is ApiResult.Success -> if (successEvent != null) _events.send(successEvent)
                is ApiResult.Failure -> error = result.message
            }
            loading = false
        }
    }
}
