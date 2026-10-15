package com.clickbeans.wifidatabase.ui.detail

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.clickbeans.wifidatabase.domain.model.PasswordCandidate
import com.clickbeans.wifidatabase.ui.REFRESH_AFTER_DETAIL
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val REPORT_REASONS = listOf(
    "wrong" to "Password is wrong",
    "abusive" to "Abusive content",
    "not_mine_to_share" to "Not theirs to share",
    "other" to "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(navController: NavController, viewModel: DetailViewModel = hiltViewModel()) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var reportTarget by remember { mutableStateOf<PasswordCandidate?>(null) }
    var didItWorkTarget by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAp by remember { mutableStateOf(false) }
    var confirmDeleteCandidate by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                viewModel.tick()
                val now = viewModel.state.elapsedRealtime
                val detail = viewModel.state.detail
                val deadlines = listOfNotNull(detail?.deletionDeadlineElapsedRealtime) +
                    detail?.passwords.orEmpty().mapNotNull { it.deletionDeadlineElapsedRealtime }
                val untilNext = deadlines.filter { it > now }.minOrNull()?.minus(now) ?: 1000L
                delay(untilNext.coerceIn(1L, 1000L))
            }
        }
    }

    LaunchedEffect(viewModel.state.detail?.passwords) {
        val remaining = viewModel.state.detail?.passwords.orEmpty().map { it.id }.toSet()
        if (reportTarget?.id !in remaining) reportTarget = null
        if (didItWorkTarget !in remaining) didItWorkTarget = null
        if (confirmDeleteCandidate !in remaining) confirmDeleteCandidate = null
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailEvent.Toast -> snackbar.showSnackbar(event.message)
                DetailEvent.AccessPointDeleted -> {
                    navController.previousBackStackEntry?.savedStateHandle?.set(REFRESH_AFTER_DETAIL, true)
                    navController.popBackStack()
                }
                is DetailEvent.CandidateDeleted -> {
                    navController.previousBackStackEntry?.savedStateHandle?.set(REFRESH_AFTER_DETAIL, true)
                    snackbar.showSnackbar("Password deleted")
                }
                is DetailEvent.PasswordPruned -> {
                    navController.previousBackStackEntry?.savedStateHandle?.set(REFRESH_AFTER_DETAIL, true)
                    Toast.makeText(context, "Password removed after community downvotes.", Toast.LENGTH_LONG).show()
                    if (event.accessPointDeleted) navController.popBackStack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(viewModel.state.detail?.ssid ?: "Access point") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (viewModel.canDeleteAccessPoint()) {
                        IconButton(
                            onClick = { confirmDeleteAp = true },
                        ) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete access point")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            when {
                viewModel.state.loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                viewModel.state.unverified -> {
                    Text(
                        "Verify your email to reveal passwords. Open the verification link from your inbox.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                viewModel.state.error != null -> {
                    Text(
                        viewModel.state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    OutlinedButton(onClick = viewModel::load) {
                        Text("Retry")
                    }
                }
                else -> {
                    val detail = viewModel.state.detail ?: return@Column
                    Text(
                        listOfNotNull(
                            detail.security,
                            "${detail.passwords.size} password candidate${if (detail.passwords.size == 1) "" else "s"}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(viewModel.ranked()) { candidate ->
                            CandidateCard(
                                candidate = candidate,
                                canDelete = viewModel.canDelete(candidate),
                                onAddToPhone = { viewModel.addToPhone(candidate) },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(candidate.password))
                                    viewModel.markCopied(candidate.id)
                                    didItWorkTarget = candidate.id
                                },
                                onVote = { value -> viewModel.vote(candidate.id, value) },
                                onRetract = { viewModel.retractVote(candidate.id) },
                                onReport = { reportTarget = candidate },
                                onDelete = { confirmDeleteCandidate = candidate.id },
                            )
                        }
                        val unlikely = viewModel.unlikely()
                        if (unlikely.isNotEmpty()) {
                            item {
                                TextButton(onClick = viewModel::toggleUnlikely) {
                                    Text(
                                        if (viewModel.state.showUnlikely) {
                                            "Hide unlikely passwords (${unlikely.size})"
                                        } else {
                                            "Show unlikely passwords (${unlikely.size})"
                                        },
                                    )
                                }
                            }
                            if (viewModel.state.showUnlikely) {
                                items(unlikely) { candidate ->
                                    CandidateCard(
                                        candidate = candidate,
                                        canDelete = viewModel.canDelete(candidate),
                                        onAddToPhone = { viewModel.addToPhone(candidate) },
                                        onCopy = {
                                            clipboard.setText(AnnotatedString(candidate.password))
                                            didItWorkTarget = candidate.id
                                        },
                                        onVote = { value -> viewModel.vote(candidate.id, value) },
                                        onRetract = { viewModel.retractVote(candidate.id) },
                                        onReport = { reportTarget = candidate },
                                        onDelete = { confirmDeleteCandidate = candidate.id },
                                    )
                                }
                            }
                        }
                        if (viewModel.state.showDonationCard) {
                            item {
                                DonationCard(
                                    onSupport = {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://github.com/sponsors/erikbeans"),
                                            ),
                                        )
                                        viewModel.dismissDonationCard(never = false)
                                    },
                                    onNotNow = { viewModel.dismissDonationCard(never = false) },
                                    onNever = { viewModel.dismissDonationCard(never = true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    didItWorkTarget?.let { passwordId ->
        AlertDialog(
            onDismissRequest = { didItWorkTarget = null },
            title = { Text("Did it work?") },
            text = { Text("Your vote helps others find the working password first.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmWorked(passwordId)
                    didItWorkTarget = null
                }) {
                    Icon(Icons.Outlined.ThumbUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Yes")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.vote(passwordId, -1)
                    didItWorkTarget = null
                }) {
                    Icon(Icons.Outlined.ThumbDown, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("No")
                }
            },
        )
    }

    reportTarget?.let { candidate ->
        var selected by remember { mutableStateOf(REPORT_REASONS.first().first) }
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("Report password") },
            text = {
                Column {
                    REPORT_REASONS.forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = value },
                        ) {
                            RadioButton(selected = selected == value, onClick = { selected = value })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.report(candidate.id, selected)
                    reportTarget = null
                }) { Text("Report") }
            },
            dismissButton = {
                TextButton(onClick = { reportTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (confirmDeleteAp) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAp = false },
            title = { Text("Delete this access point?") },
            text = {
                Text(if (viewModel.canDeleteAccessPoint()) {
                    "All its password candidates will be removed. This can't be undone."
                } else "This access point is no longer available to delete.")
            },
            confirmButton = {
                TextButton(enabled = viewModel.canDeleteAccessPoint(), onClick = {
                    confirmDeleteAp = false
                    viewModel.deleteAccessPoint()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAp = false }) { Text("Cancel") }
            },
        )
    }

    confirmDeleteCandidate?.let { passwordId ->
        val candidate = viewModel.state.detail?.passwords?.find { it.id == passwordId }
        val canDelete = candidate?.let(viewModel::canDelete) == true
        AlertDialog(
            onDismissRequest = { confirmDeleteCandidate = null },
            title = { Text("Delete this password?") },
            text = {
                Text(if (canDelete) "Your contributed password will be removed. This can't be undone."
                else "This password is no longer available to delete.")
            },
            confirmButton = {
                TextButton(enabled = canDelete, onClick = {
                    confirmDeleteCandidate = null
                    viewModel.deleteCandidate(passwordId)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteCandidate = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DonationCard(
    onSupport: () -> Unit,
    onNotNow: () -> Unit,
    onNever: () -> Unit,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Glad it worked!", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "WiFi Database stays free because people share what they know. " +
                    "If you'd like to support its development, you can buy us a coffee.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSupport) {
                    Icon(Icons.Outlined.FavoriteBorder, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Support")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onNotNow) { Text("Not now") }
                TextButton(onClick = onNever) { Text("Never") }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: PasswordCandidate,
    canDelete: Boolean,
    onAddToPhone: () -> Unit,
    onCopy: () -> Unit,
    onVote: (Int) -> Unit,
    onRetract: () -> Unit,
    onReport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(candidate.password, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "score ${candidate.score}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (candidate.score >= 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    if (candidate.myVote == 1) onRetract() else onVote(1)
                }) {
                    Icon(
                        Icons.Outlined.ThumbUp,
                        contentDescription = "Vote up",
                        tint = if (candidate.myVote == 1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text("${candidate.upVotes}", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = {
                    if (candidate.myVote == -1) onRetract() else onVote(-1)
                }) {
                    Icon(
                        Icons.Outlined.ThumbDown,
                        contentDescription = "Vote down",
                        tint = if (candidate.myVote == -1) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text("${candidate.downVotes}", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAddToPhone) {
                    Icon(Icons.Outlined.Wifi, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add to phone")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Copy")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Report") },
                        leadingIcon = { Icon(Icons.Outlined.Flag, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onReport()
                        },
                    )
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text("Delete my password") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}
