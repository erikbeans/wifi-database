package com.clickbeans.wifidatabase.ui.auth

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.clickbeans.wifidatabase.ui.Routes
import com.clickbeans.wifidatabase.ui.common.CredentialFieldKind
import com.clickbeans.wifidatabase.ui.common.CredentialTextField

@Composable
fun LoginScreen(navController: NavController, viewModel: AuthViewModel = hiltViewModel()) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthEvent.LoggedIn) {
                navController.navigate(Routes.NEARBY) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    AuthForm(
        title = "Welcome back",
        subtitle = "Sign in to find and share WiFi passwords.",
        submitLabel = "Sign in",
        navController = navController,
        viewModel = viewModel,
        isLogin = true,
        email = email,
        password = password,
        onEmailChange = { email = it },
        onPasswordChange = { password = it },
        onSubmit = { viewModel.login(email.trim(), password) },
    )
}

@Composable
fun RegisterScreen(navController: NavController, viewModel: AuthViewModel = hiltViewModel()) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AuthEvent.Registered) {
                navController.navigate("check-email?email=${Uri.encode(email.trim())}") {
                    launchSingleTop = true
                }
            }
        }
    }

    AuthForm(
        title = "Create an account",
        subtitle = "Register with your email; we'll send a verification link.",
        submitLabel = "Register",
        navController = navController,
        viewModel = viewModel,
        isLogin = false,
        email = email,
        password = password,
        onEmailChange = { email = it },
        onPasswordChange = { password = it },
        onSubmit = { viewModel.register(email.trim(), password) },
    )
}

@Composable
private fun AuthForm(
    title: String,
    subtitle: String,
    submitLabel: String,
    navController: NavController,
    viewModel: AuthViewModel,
    isLogin: Boolean,
    email: String,
    password: String,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        CredentialTextField(
            value = email,
            onValueChange = onEmailChange,
            label = "Email",
            kind = CredentialFieldKind.Email,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        CredentialTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = "Password",
            kind = if (isLogin) CredentialFieldKind.Password else CredentialFieldKind.NewPassword,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!isLogin) {
            Spacer(Modifier.height(4.dp))
            Text(
                "At least 8 characters",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = !viewModel.loading && email.isNotBlank() && password.length >= 8,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (viewModel.loading) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            } else {
                Text(submitLabel)
            }
        }
        viewModel.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (isLogin) {
            TextButton(onClick = { navController.navigate(Routes.REGISTER) }) {
                Text("Need an account? Register")
            }
            TextButton(onClick = { navController.navigate(Routes.FORGOT_PASSWORD) }) {
                Text("Forgot password?")
            }
        } else {
            TextButton(onClick = { navController.popBackStack() }) {
                Text("Already registered? Sign in")
            }
        }
    }
}
