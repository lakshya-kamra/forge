package com.laksh.forge.feature.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ChatMessage
import com.laksh.forge.domain.KnowledgeBase
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoachState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: String = "",
    val isStreaming: Boolean = false,
    val error: String? = null,
    val hasApiKey: Boolean = true,
    val goalLabel: String = ""
)

class CoachViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val local = MutableStateFlow(Triple("", false, null as String?))

    val state: StateFlow<CoachState> = combine(
        repo.chatDao.observeAll(),
        local,
        repo.settingsStore.settings
    ) { messages, l, settings ->
        CoachState(
            messages = messages,
            streaming = l.first,
            isStreaming = l.second,
            error = l.third,
            hasApiKey = settings.hasApiKey,
            goalLabel = settings.goal.label
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachState())

    fun dismissError() = local.update { it.copy(third = null) }

    fun clearHistory() = viewModelScope.launch { repo.chatDao.clear() }

    fun send(text: String) {
        val message = text.trim()
        if (message.isBlank() || local.value.second) return

        viewModelScope.launch {
            val settings = repo.settings()
            if (!settings.hasApiKey) {
                local.update { it.copy(third = "Add your Anthropic API key in Settings to use the coach.") }
                return@launch
            }

            val now = System.currentTimeMillis()
            repo.chatDao.insert(ChatMessage(role = "user", content = message, timestamp = now))

            // Only the stable knowledge goes in `system` so the cached prefix survives
            // between turns; the changing data snapshot rides along with the user turn.
            val system = KnowledgeBase.TRAINING_AND_NUTRITION + "\n\n" + KnowledgeBase.COACH_PERSONA

            val history = repo.chatDao.lastN(21)
                .sortedBy { it.timestamp }
                .dropLast(1)                       // the message just inserted
                .takeLast(20)
                .map { it.role to it.content }

            val snapshot = runCatching { repo.buildCoachContext() }.getOrDefault("")
            val userTurn = if (snapshot.isBlank()) message
            else "$snapshot\n\n$message"

            local.update { Triple("", true, null) }

            val builder = StringBuilder()
            runCatching {
                repo.ai.chatStream(system, history, userTurn).collect { delta ->
                    builder.append(delta)
                    local.update { it.copy(first = builder.toString()) }
                }
            }.onSuccess {
                val reply = builder.toString().trim()
                if (reply.isNotEmpty()) {
                    repo.chatDao.insert(
                        ChatMessage(
                            role = "assistant",
                            content = reply,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                local.update { Triple("", false, null) }
            }.onFailure { e ->
                // Keep whatever streamed before the failure rather than losing it.
                val partial = builder.toString().trim()
                if (partial.isNotEmpty()) {
                    repo.chatDao.insert(
                        ChatMessage(
                            role = "assistant",
                            content = partial,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                local.update { Triple("", false, e.message ?: "The coach couldn't reply.") }
            }
        }
    }

    companion object {
        val Factory = forgeFactory { CoachViewModel(it) }

        val STARTERS = listOf(
            "Am I on track for my goal?",
            "Is my protein intake high enough?",
            "How is my training volume looking?",
            "Why has my weight gone up this week?"
        )
    }
}
