package com.laksh.forge.feature.train

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.Program
import com.laksh.forge.data.db.ProgramDay
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill
import com.laksh.forge.ui.components.SectionHeader
import com.laksh.forge.util.Dates

@Composable
fun TrainScreen(
    onOpenSession: (Long) -> Unit,
    vm: TrainViewModel = viewModel(factory = TrainViewModel.Factory)
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Program?>(null) }

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importPdf(uri) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Train",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Pill(
                    "Import PDF",
                    icon = Icons.Outlined.UploadFile,
                    filled = true,
                    onClick = { pickPdf.launch(arrayOf("application/pdf")) }
                )
            }
        }

        state.openSession?.let { open ->
            item {
                ForgeCard(
                    onClick = { onOpenSession(open.id) },
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Bolt, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Session in progress",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${open.title} · ${Dates.durationLabel(open.startedAt, null)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Pill("Resume", filled = true, onClick = { onOpenSession(open.id) })
                    }
                }
            }
        }

        when (state.importPhase) {
            ImportPhase.READING, ImportPhase.PARSING -> item {
                ForgeCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            state.importStatus,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            ImportPhase.DONE -> item {
                ForgeCard {
                    Text(state.importStatus, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Pill("Dismiss", onClick = vm::clearImportState)
                }
            }
            ImportPhase.ERROR -> item {
                ForgeCard(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)) {
                    Text(
                        state.importError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Pill("Dismiss", onClick = vm::clearImportState)
                }
            }
            ImportPhase.IDLE -> Unit
        }

        val active = state.active
        if (active == null) {
            item {
                ForgeCard {
                    EmptyState(
                        icon = Icons.Outlined.FitnessCenter,
                        title = "No program yet",
                        body = "Import the PDF of the program you're running - a Jeff Nippard block, a coach's spreadsheet export, anything. It'll be turned into loggable training days.",
                        action = {
                            Pill(
                                "Import a PDF",
                                icon = Icons.Outlined.UploadFile,
                                filled = true,
                                onClick = { pickPdf.launch(arrayOf("application/pdf")) }
                            )
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Pill(
                        "Start a free session",
                        icon = Icons.Outlined.PlayArrow,
                        onClick = { vm.startSession(null, onOpenSession) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        } else {
            item {
                SectionHeader(active.name.uppercase())
            }
            active.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                item {
                    ForgeCard {
                        Text(
                            "Program notes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(notes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.days) { day ->
                DayCard(day = day, onStart = { vm.startSession(day, onOpenSession) })
            }
            item {
                Pill(
                    "Free session",
                    icon = Icons.Outlined.PlayArrow,
                    onClick = { vm.startSession(null, onOpenSession) }
                )
            }
        }

        if (state.programs.size > 1) {
            item { SectionHeader("All programs") }
            items(state.programs) { p ->
                ForgeCard(onClick = { vm.activate(p) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                buildString {
                                    p.author?.let { append("$it · ") }
                                    append(if (p.isActive) "Active" else "Tap to activate")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (p.isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Pill(
                            "Delete",
                            color = MaterialTheme.colorScheme.error,
                            onClick = { confirmDelete = p }
                        )
                    }
                }
            }
        }

        if (state.sessions.any { it.endedAt != null }) {
            item { SectionHeader("History") }
            items(state.sessions.filter { it.endedAt != null }) { s ->
                ForgeCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${Dates.dayLabel(s.localDate)} · ${Dates.durationLabel(s.startedAt, s.endedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete program?") },
            text = { Text("${p.name} and its training days will be removed. Logged sessions are kept.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteProgram(p); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DayCard(day: ProgramDay, onStart: () -> Unit) {
    ForgeCard(onClick = onStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    day.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                day.focus?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(contentAlignment = Alignment.Center) {
                Pill("Start", icon = Icons.Outlined.PlayArrow, filled = true, onClick = onStart)
            }
        }
    }
}
