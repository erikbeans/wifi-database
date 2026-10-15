package com.clickbeans.wifidatabase.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.clickbeans.wifidatabase.R

@Composable
internal fun WelcomeGate(
    completed: Boolean,
    onComplete: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (completed) content() else WelcomeScreen(onContinue = onComplete)
}

@Composable
internal fun WelcomeScreen(onContinue: () -> Unit, isReplay: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        if (isReplay) {
            IconButton(onClick = onContinue, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.welcome_back))
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painterResource(R.drawable.ic_wifi_map_pin),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp, 84.dp),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.welcome_description),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        stringResource(R.string.welcome_things_to_try),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    WelcomeTip(
                        icon = Icons.Outlined.Add,
                        title = stringResource(R.string.welcome_share_title),
                        description = stringResource(R.string.welcome_share_description),
                    )
                    WelcomeTip(
                        icon = Icons.Outlined.Map,
                        title = stringResource(R.string.welcome_travel_title),
                        description = stringResource(R.string.welcome_travel_description),
                    )
                    WelcomeTip(
                        icon = Icons.Outlined.ThumbUp,
                        title = stringResource(R.string.welcome_vote_title),
                        description = stringResource(R.string.welcome_vote_description),
                    )
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Button(
                onClick = onContinue,
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (isReplay) R.string.welcome_done else R.string.welcome_get_started))
            }
        }
    }
}

@Composable
private fun WelcomeTip(icon: ImageVector, title: String, description: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
