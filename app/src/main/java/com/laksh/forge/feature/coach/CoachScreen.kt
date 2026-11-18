package com.laksh.forge.feature.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laksh.forge.data.db.ChatMessage
import com.laksh.forge.ui.components.EmptyState
import com.laksh.forge.ui.components.ForgeCard
import com.laksh.forge.ui.components.Pill

@Composable
fun CoachScreen(vm: CoachViewModel = viewModel(factory = CoachViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the stream so the newest text stays on screen.
    LaunchedEffect(state.messages.size, state.streaming) {
        val count = state.messages.size + if (state.isStreaming) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
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
                if (state.goalLabel.isNotBlank()) {
                    Text(
                        "Knows your logs · goal: ${state.goalLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.messages.isNotEmpty()) {
                IconButton(onClick = vm::clearHistory) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        "Clear conversation",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.messages.isEmpty() && !state.isStreaming) {
                item {
                    Column {
                        ForgeCard {
                            EmptyState(
                                icon = Icons.Outlined.Psychology,
                                title = "Ask about your own data",
                                body = "The coach can see your intake, weight trend, training log and wearable data, and reasons from current training and nutrition evidence."
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        CoachViewModel.STARTERS.forEach { starter ->
                            Pill(
                                starter,
                                modifier = Modifier.padding(vertical = 4.dp),
                                onClick = { vm.send(starter) }
                            )
                        }
                    }
                }
            }

            items(state.messages, key = { it.id }) { msg -> Bubble(msg) }

            if (state.isStreaming) {
                item {
                    if (state.streaming.isBlank()) ThinkingBubble()
                    else Bubble(
                        ChatMessage(
                            id = -1,
                            role = "assistant",
                            content = state.streaming,
                            timestamp = 0
                        )
                    )
                }
            }
        }

        state.error?.let { err ->
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                ForgeCard(
                    onClick = vm::dismissError,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Text(err, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Ask your coach") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            val canSend = input.isNotBlank() && !state.isStreaming
            IconButton(
                onClick = {
                    if (canSend) {
                        vm.send(input)
                        input = ""
                    }
                },
                enabled = canSend,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(25.dp))
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
}

@Composable
private fun Bubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 6.dp,
                        bottomEnd = if (isUser) 6.dp else 20.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainer
                )
                .padding(horizontal = 15.dp, vertical = 11.dp)
        ) {
            Text(
                text = stripMarkdown(msg.content),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 13.dp)
        ) {
            Text(
                "Thinking...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The coach is told to keep formatting light, but models still reach for bold and
 * bullets. Rather than pull in a Markdown renderer, flatten the few markers that
 * actually show up so the bubble never displays raw asterisks.
 */
private fun stripMarkdown(text: String): String =
    text.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")
        .replace(Regex("""^#{1,6}\s*""", RegexOption.MULTILINE), "")
        .lines()
        .joinToString("\n") { line ->
            val t = line.trimStart()
            if (t.startsWith("- ") || t.startsWith("* ")) "  • " + t.drop(2) else line
        }
        .trim()
