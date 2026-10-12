package com.erikbeans.wifidatabase.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.erikbeans.wifidatabase.data.api.ApiResult
import com.erikbeans.wifidatabase.domain.repository.AuthRepository
import com.erikbeans.wifidatabase.ui.Routes
import com.erikbeans.wifidatabase.ui.common.CredentialFieldKind
import com.erikbeans.wifidatabase.ui.common.CredentialTextField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VerifyUiState(
    val loading: Boolean = true,
    val success: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class VerifyViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VerifyUiState())
    val state: StateFlow<VerifyUiState> = _state.asStateFlow()

    fun verify(token: String) {
        if (token.isBlank()) {
            _state.value = VerifyUiState(loading = false, error = "This link is missing its token — open it from your email app.")
            return
        }
        viewModelScope.launch {
            when (val result = authRepository.verify(token)) {
                is ApiResult.Success -> _state.value = VerifyUiState(loading = false, success = true)
                is ApiResult.Failure ->
                    _state.value = VerifyUiState(loading = false, error = result.message)
            }
        }
    }
}

@Composable
fun VerifyScreen(navController: NavController, token: String, viewModel: VerifyViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(token) { viewModel.verify(token) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.size(48.dp))
            state.success -> {
                Text("Email verified!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your account is ready. You're signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = {
                    navController.navigate(Routes.NEARBY) { popUpTo(0) { inclusive = true } }
                }) {
                    Text("Start exploring")
                }
            }
            else -> {
                Icon(Icons.Outlined.Email, contentDescription = null)
                Spacer(Modifier.height(12.dp))
                Text("Verification failed", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.error ?: "This link has expired or was already used.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } } }) {
                    Text("Back to sign in")
                }
            }
        }
    }
}

@Composable
fun ResetPasswordScreen(navController: NavController, token: String, viewModel: AuthViewModel = hiltViewModel()) {
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthEvent.PasswordChanged) {
                navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Choose a new password", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your reset link is valid for 24 hours and can be used once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        CredentialTextField(
            value = password,
            onValueChange = { password = it },
            label = "New password",
            kind = CredentialFieldKind.NewPassword,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "At least 8 characters",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.confirmPasswordReset(token, password) },
            enabled = !viewModel.loading && password.length >= 8,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Set new password")
        }
        viewModel.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
