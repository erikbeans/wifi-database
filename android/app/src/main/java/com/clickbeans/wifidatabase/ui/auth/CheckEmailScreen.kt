package com.clickbeans.wifidatabase.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.navigation.NavController
import com.clickbeans.wifidatabase.ui.Routes
import com.clickbeans.wifidatabase.ui.common.CredentialFieldKind
import com.clickbeans.wifidatabase.ui.common.CredentialTextField

@Composable
fun CheckEmailScreen(
    navController: NavController,
    email: String,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.MarkEmailRead, contentDescription = null)
        Spacer(Modifier.height(16.dp))
        Text("Check your email", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "We sent a verification link to ${email.ifBlank { "your address" }}. " +
                "Open it on this device to finish signing up.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { viewModel.resendVerification(email) },
            enabled = !viewModel.loading,
        ) {
            Text(if (viewModel.resendRequested) "Email resent" else "Resend verification email")
        }
        viewModel.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { navController.popBackStack(Routes.LOGIN, inclusive = false) }) {
            Text("Back to sign in")
        }
    }
}

@Composable
fun ForgotPasswordScreen(navController: NavController, viewModel: AuthViewModel = hiltViewModel()) {
    var email by rememberSaveable { mutableStateOf("") }
    var sent by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthEvent.ResetEmailSent) sent = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reset your password", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your email and we'll send a reset link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        CredentialTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email",
            kind = CredentialFieldKind.Email,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.requestPasswordReset(email.trim()) },
            enabled = !viewModel.loading && email.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send reset email")
        }
        if (sent) {
            Spacer(Modifier.height(12.dp))
            Text(
                "If that address is registered, a reset email is on its way.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
        viewModel.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { navController.popBackStack() }) {
            Text("Back to sign in")
        }
    }
}
