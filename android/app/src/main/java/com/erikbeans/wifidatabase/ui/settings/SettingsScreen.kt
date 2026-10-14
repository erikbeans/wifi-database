package com.erikbeans.wifidatabase.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.erikbeans.wifidatabase.BuildConfig
import com.erikbeans.wifidatabase.ui.Routes

private const val SPONSORS_URL = "https://github.com/sponsors/erikbeans"
private const val PRIVACY_URL = "https://wifidb.clickbeans.com/privacy"
private const val TERMS_URL = "https://wifidb.clickbeans.com/terms"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: SettingsViewModel = hiltViewModel()) {
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun openLink(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            ListItem(
                headlineContent = { Text("Signed in as") },
                supportingContent = { Text(viewModel.email ?: "unknown") },
            )
            Spacer(Modifier.height(16.dp))
            ListItem(
                headlineContent = { Text("Support development") },
                supportingContent = { Text("WiFi Database is free — donations keep the server running") },
                leadingContent = {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openLink(SPONSORS_URL) },
            )
            ListItem(
                headlineContent = { Text("Privacy statement") },
                supportingContent = { Text(PRIVACY_URL) },
                leadingContent = { Icon(Icons.Outlined.PrivacyTip, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openLink(PRIVACY_URL) },
            )
            ListItem(
                headlineContent = { Text("Terms of service") },
                supportingContent = { Text(TERMS_URL) },
                leadingContent = { Icon(Icons.Outlined.Description, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openLink(TERMS_URL) },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    viewModel.logout {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                },
                enabled = !viewModel.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Log out")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Deleting your account removes your credentials and anonymizes your " +
                    "contributions — shared networks and passwords stay available to others.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { confirmDelete = true },
                enabled = !viewModel.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete my account")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "WiFi Database ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("You'll be signed out everywhere. Your shared passwords stay in the database but become anonymized.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount {
                        navController.navigate(Routes.LOGIN) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }) { Text("Delete account") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
