package com.laksh.forge.feature.coach

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.domain.guardrails.CrisisResource
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Composable
fun CoachScreen(vm: CoachViewModel = viewModel(factory = CoachViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current

    val send: (String) -> Unit = remember(vm, state.haptics) {
        { text ->
            if (state.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            vm.send(text)
        }
    }

    // The list is reversed (newest at index 0) so a growing reply stays anchored to the
    // bottom without scrolling on every token; only new rows trigger a scroll.
    LaunchedEffect(state.rows.size) {
        if (state.rows.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Header(
            modelLabel = state.modelLabel,
            aiConfigured = state.aiConfigured,
            goalLabel = state.goalLabel,
            canClear = state.hasMessages,
            onClear = { confirmClear = true }
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> LoadingIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                )
                state.rows.isEmpty() -> EmptyCoach(
                    aiConfigured = state.aiConfigured,
                    onPrompt = send
                )
                else -> {
                    val reversed = remember(state.rows) { state.rows.asReversed() }
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
                    ) {
                        items(
                            items = reversed,
                            key = { it.key },
                            contentType = { row ->
                                when (row) {
                                    is CoachRow.Message -> if (row.message.role == "user") "user" else "assistant"
                                    is CoachRow.Notice -> "notice"
                                    is CoachRow.Streaming -> "streaming"
                                    CoachRow.Thinking -> "thinking"
                                }
                            }
                        ) { row ->
                            when (row) {
                                is CoachRow.Message -> {
                                    // The saved copy of a just-finished reply takes the stream's
                                    // place with no fade, so the swap is invisible.
                                    val anim = if (row.replacedStream) {
                                        Modifier.animateItem(fadeInSpec = null)
                                    } else Modifier.animateItem()
                                    MessageBubble(row, anim)
                                }
                                is CoachRow.Notice -> NoticeCard(row.notice, Modifier.animateItem())
                                is CoachRow.Streaming -> StreamingBubble(
                                    row.text,
                                    row.model,
                                    Modifier.animateItem(fadeOutSpec = null)
                                )
                                CoachRow.Thinking -> ThinkingBubble(Modifier.animateItem())
                            }
                        }
                    }
                }
            }
        }

        state.error?.let { err ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ForgeCard(
                    onClick = vm::dismissError,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (state.rows.isNotEmpty() && !state.busy) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CoachViewModel.QUICK_PROMPTS, key = { it }) { prompt ->
                    SuggestionChip(
                        onClick = { send(prompt) },
                        label = { Text(prompt) }
                    )
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = !state.busy,
            onSend = {
                send(input)
                input = ""
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the conversation?") },
            text = { Text("This deletes every message with the coach on this phone. Your logs, plan and settings are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    if (state.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    vm.clearHistory()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun Header(
    modelLabel: String,
    aiConfigured: Boolean,
    goalLabel: String,
    canClear: Boolean,
    onClear: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Coach",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold
            )
            val subtitle = when {
                !aiConfigured -> "No AI provider set up"
                modelLabel.isNotBlank() && goalLabel.isNotBlank() -> "$modelLabel · goal: $goalLabel"
                modelLabel.isNotBlank() -> modelLabel
                else -> "Knows your logs · goal: $goalLabel"
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canClear) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.DeleteSweep,
                    "Clear conversation",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyCoach(aiConfigured: Boolean, onPrompt: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        ForgeCard {
            EmptyState(
                icon = Icons.Outlined.Psychology,
                title = "Ask about your own data",
                body = if (aiConfigured) {
                    "The coach sees your plan, intake, weight trend, training volume and wearable data, and reasons from reviewed training and nutrition research."
                } else {
                    "Set up an AI provider in Settings and the coach can answer from your plan, intake, weight trend, training and wearable data."
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Try asking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoachViewModel.QUICK_PROMPTS.forEach { prompt ->
                SuggestionChip(
                    onClick = { onPrompt(prompt) },
                    label = { Text(prompt) },
                    enabled = aiConfigured
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Ask your coach") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            maxLines = 5
        )
        Spacer(Modifier.width(8.dp))
        val canSend = value.isNotBlank() && enabled
        IconButton(
            onClick = { if (canSend) onSend() },
            enabled = canSend,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                "Send",
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val UserShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp)
private val AssistantShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp)

@Composable
private fun MessageBubble(row: CoachRow.Message, modifier: Modifier = Modifier) {
    val isUser = row.message.role == "user"
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .clip(if (isUser) UserShape else AssistantShape)
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .padding(horizontal = 15.dp, vertical = 11.dp)
        ) {
            if (isUser) {
                Text(
                    row.message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                MarkdownText(row.message.content, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (!isUser && !row.model.isNullOrBlank()) ModelCaption(row.model)
    }
}

@Composable
private fun ModelCaption(model: String) {
    Text(
        model,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(start = 10.dp, top = 3.dp)
    )
}

/**
 * The live reply. Tokens arrive in uneven bursts; revealing characters on the frame clock at
 * a speed proportional to the backlog turns those bursts into a steady type-on that always
 * catches up within a few frames, at whatever refresh rate the panel is running.
 */
@Composable
private fun StreamingBubble(text: String, model: String, modifier: Modifier = Modifier) {
    val target = rememberUpdatedState(text)
    var shown by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        snapshotFlow { target.value.length }.collectLatest { length ->
            if (shown > length) shown = length
            var last = 0L
            while (shown < length) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 1.0 / 120.0 else (now - last) / 1_000_000_000.0
                    last = now
                    val backlog = length - shown
                    // At least ~60 chars/s, and fast enough to clear the backlog in ~1/8 s.
                    val perSecond = max(MIN_CHARS_PER_SECOND, backlog * CATCH_UP_PER_SECOND)
                    val step = max(1, ceil(perSecond * dt).toInt())
                    shown = min(length, shown + step)
                }
            }
        }
    }

    val visible = text.substring(0, shown.coerceIn(0, text.length))
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .clip(AssistantShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 15.dp, vertical = 11.dp)
        ) {
            MarkdownText(visible, color = MaterialTheme.colorScheme.onSurface)
        }
        if (model.isNotBlank()) ModelCaption(model)
    }
}

private const val MIN_CHARS_PER_SECOND = 60.0
private const val CATCH_UP_PER_SECOND = 8.0

@Composable
private fun ThinkingBubble(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .clip(AssistantShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            LoadingIndicator(
                Modifier.size(40.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun NoticeCard(notice: CoachNotice, modifier: Modifier = Modifier) {
    when (notice) {
        is CoachNotice.Crisis -> CrisisCard(notice.lockApplied, modifier)
        is CoachNotice.Safety -> SafetyCard(notice, modifier)
    }
}

/** safety_crisis_disclosure: shown immediately, independent of any model reply. */
@Composable
private fun CrisisCard(lockApplied: Boolean, modifier: Modifier = Modifier) {
    val onContainer = MaterialTheme.colorScheme.onTertiaryContainer
    ForgeCard(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SupportAgent, null, tint = onContainer, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "You don't have to handle this alone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = onContainer
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(Guardrails.CRISIS_MESSAGE, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Guardrails.crisisResources.forEach { CallButton(it) }
        }
        if (lockApplied) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Forge has switched your plan to maintenance and paused weight-loss features for now.",
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun SafetyCard(notice: CoachNotice.Safety, modifier: Modifier = Modifier) {
    val onContainer = MaterialTheme.colorScheme.onErrorContainer
    ForgeCard(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (notice.flag.id.startsWith("safety_medical")) Icons.Outlined.HealthAndSafety
                else Icons.Outlined.ReportProblem,
                null,
                tint = onContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(notice.flag.message, style = MaterialTheme.typography.bodyMedium, color = onContainer)
        }
        if (notice.emergency) {
            Spacer(Modifier.height(12.dp))
            CallButton(Guardrails.crisisResources.first { it.contact == "112" })
        }
    }
}

/** Opens the dialer pre-filled; ACTION_DIAL needs no permission and lets the person decide. */
@Composable
private fun CallButton(resource: CrisisResource) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = {
            val digits = resource.contact.filter { it.isDigit() }
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Outlined.Call, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("${resource.name} · ${resource.contact}", fontWeight = FontWeight.SemiBold)
            Text(
                resource.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
