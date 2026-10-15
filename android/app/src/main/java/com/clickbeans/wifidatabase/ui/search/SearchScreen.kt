package com.clickbeans.wifidatabase.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterCenterFocus
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.clickbeans.wifidatabase.domain.model.NearbyAccessPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(navController: NavController, viewModel: SearchViewModel = hiltViewModel()) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = viewModel.state.query,
                onValueChange = viewModel::updateQuery,
                placeholder = { Text("Search by network name") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                enabled = !viewModel.state.nearMe,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FilterChip(
                selected = viewModel.state.nearMe,
                onClick = viewModel::toggleNearMe,
                label = { Text("Near me instead") },
                leadingIcon = { Icon(Icons.Outlined.FilterCenterFocus, contentDescription = null) },
            )
            if (viewModel.state.loading) {
                CircularProgressIndicator(Modifier.padding(16.dp))
            }
            viewModel.state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (!viewModel.state.loading && viewModel.state.results.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (viewModel.state.nearMe) {
                        Text(
                            "Nothing shared nearby yet.\nShare the first network from the Nearby tab!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "No networks match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(viewModel.state.results) { ap ->
                    AccessPointRow(ap) { navController.navigate("detail/${ap.id}") }
                }
            }
        }
    }
}

@Composable
private fun AccessPointRow(ap: NearbyAccessPoint, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(ap.ssid) },
        supportingContent = {
            Row {
                if (ap.security != null) Text(ap.security)
                ap.distanceM?.let { Text(" · %.0f m".format(it)) }
                Text(" · ${ap.passwordCount} password${if (ap.passwordCount == 1) "" else "s"}")
            }
        },
        trailingContent = {
            Text(
                ap.bestScore?.let { "score $it" } ?: "—",
                style = MaterialTheme.typography.labelMedium,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
