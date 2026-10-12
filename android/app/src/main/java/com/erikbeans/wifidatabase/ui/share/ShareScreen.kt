package com.erikbeans.wifidatabase.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    navController: NavController,
    prefilledSsid: String,
    prefilledBssid: String,
    prefilledSecurity: String,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    var bssid by rememberSaveable { mutableStateOf(prefilledBssid) }
    var security by rememberSaveable { mutableStateOf(prefilledSecurity) }

    LaunchedEffect(prefilledSsid) {
        viewModel.prefill(prefilledSsid, prefilledBssid, prefilledSecurity)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ShareEvent.Shared -> navController.popBackStack()
                is ShareEvent.DuplicateUpvote -> navController.popBackStack()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Share a WiFi password") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            OutlinedTextField(
                value = viewModel.state.ssid,
                onValueChange = viewModel::updateSsid,
                label = { Text("Network name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = viewModel.state.password,
                onValueChange = viewModel::updatePassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (viewModel.state.showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = viewModel::toggleShowPassword) {
                        Text(if (viewModel.state.showPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val coords = viewModel.state.latitude
            if (coords != null) {
                Text(
                    "Location: %.5f, %.5f (from your GPS fix — approximate router position)".format(
                        viewModel.state.latitude,
                        viewModel.state.longitude,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Getting your GPS location…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = viewModel.state.consentConfirmed,
                    onCheckedChange = { viewModel.toggleConsent() },
                )
                Text(
                    "I confirm I'm allowed to share this network's password.\nShared passwords become public.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.submit(bssid, security) },
                enabled = !viewModel.state.submitting &&
                    viewModel.state.ssid.isNotBlank() &&
                    viewModel.state.password.isNotBlank() &&
                    viewModel.state.consentConfirmed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (viewModel.state.submitting) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Share password")
                }
            }
            viewModel.state.error?.let { error ->
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
