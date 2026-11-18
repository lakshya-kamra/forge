package com.laksh.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.feature.settings.SettingsViewModel
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill

/**
 * First run. Two things actually need setting before the app is useful: the API key and
 * the goal. Everything else has a sane default and can be changed later in Settings.
 */
@Composable
fun OnboardingScreen(vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Forge",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Calories, training and bodyweight in one place - with a coach that can actually see your data.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))
        FeatureLine(Icons.Outlined.CameraAlt, "Photograph a meal", "Estimates lean deliberately high, so you under-count less.")
        FeatureLine(Icons.Outlined.FitnessCenter, "Import your program PDF", "Turned into loggable days with sets, reps and RIR.")
        FeatureLine(Icons.Outlined.Insights, "Trend weight and learned TDEE", "Real maintenance worked out from your own data.")
        FeatureLine(Icons.Outlined.Psychology, "Ask the coach", "It reads your logs and current training evidence.")

        Spacer(Modifier.height(28.dp))
        ForgeCard {
            Text(
                "Your Anthropic API key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Get one at console.anthropic.com. It stays on this phone, in Forge's private storage, and is used only for your own requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = { Text("sk-ant-...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            Pill(if (showKey) "Hide" else "Show", onClick = { showKey = !showKey })
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "What are you doing right now?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        GoalMode.entries.forEach { g ->
            val selected = state.settings.goal == g
            ForgeCard(
                onClick = { vm.setGoal(g) },
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceContainer,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text(
                    g.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    g.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (key.isBlank()) {
                Pill("Skip for now", onClick = { vm.setOnboarded(true) })
                Spacer(Modifier.width(8.dp))
            }
            Pill(
                "Start",
                filled = true,
                onClick = {
                    if (key.isNotBlank()) vm.setApiKey(key)
                    vm.setOnboarded(true)
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "You can add the key later in Settings. Without one, manual logging and barcode scanning still work.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, title: String, body: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
