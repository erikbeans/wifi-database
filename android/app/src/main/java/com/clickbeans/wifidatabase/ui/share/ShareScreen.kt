package com.clickbeans.wifidatabase.ui.share

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    navController: NavController,
    prefilledSsid: String,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    LaunchedEffect(prefilledSsid) {
        viewModel.preselect(prefilledSsid)
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
            if (viewModel.state.networks.isEmpty()) {
                Text(
                    "No scanned networks yet. WiFi Database only shares networks your phone " +
                        "can actually see — allow location access and scan from the Nearby screen first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = viewModel::rescan, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan for networks")
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = viewModel.state.dropdownOpen,
                    onExpandedChange = { expanded ->
                        if (expanded) viewModel.openDropdown() else viewModel.dismissDropdown()
                    },
                ) {
                    OutlinedTextField(
                        value = viewModel.state.selected?.ssid ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Network (from your scan)") },
                        placeholder = { Text("Pick a network") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = viewModel.state.dropdownOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = viewModel.state.dropdownOpen,
                        onDismissRequest = viewModel::dismissDropdown,
                    ) {
                        viewModel.state.networks.forEach { network ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (network.security != null) {
                                            "${network.ssid} · ${network.security}"
                                        } else {
                                            network.ssid
                                        }
                                    )
                                },
                                onClick = { viewModel.selectNetwork(network) },
                            )
                        }
                    }
                }
            }
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
                supportingText = { Text("8–63 characters") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            val pinLat = viewModel.state.pinLatitude
            val pinLon = viewModel.state.pinLongitude
            if (pinLat != null && pinLon != null) {
                Text(
                    "Location: %.5f, %.5f".format(pinLat, pinLon) +
                        " (approximate router position — drag the pin on the map to correct it)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (viewModel.state.mapExpanded) {
                    Spacer(Modifier.height(8.dp))
                    LocationPickerMap(
                        latitude = pinLat,
                        longitude = pinLon,
                        onPinMoved = viewModel::adjustPin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::useThisLocation, modifier = Modifier.fillMaxWidth()) {
                        Text("Use this location")
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = viewModel::expandMap) {
                        Text("Adjust on map")
                    }
                }
            } else {
                Text(
                    "Getting your GPS location…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row {
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
                onClick = viewModel::submit,
                enabled = !viewModel.state.submitting &&
                    viewModel.state.selected != null &&
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
