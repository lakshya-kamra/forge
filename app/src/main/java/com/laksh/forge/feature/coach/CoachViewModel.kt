package com.laksh.forge.feature.coach

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.laksh.forge.data.ForgeRepository
import com.laksh.forge.data.db.ChatMessage
import com.laksh.forge.data.remote.Task
import com.laksh.forge.domain.KnowledgeBase
import com.laksh.forge.domain.guardrails.GuardrailFlag
import com.laksh.forge.domain.guardrails.Guardrails
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A deterministic safety card shown under the user message that triggered it. */
@Immutable
sealed interface CoachNotice {
    /** safety_crisis_disclosure: help lines, shown before any model output. */
    data class Crisis(val lockApplied: Boolean) : CoachNotice
    /** safety_medical_red_flags_stop or training_pain_stop_and_refer. */
    data class Safety(val flag: GuardrailFlag, val emergency: Boolean) : CoachNotice
}

/** One row of the conversation, in display order (oldest first). */
@Immutable
sealed interface CoachRow {
    val key: String

    data class Message(
        val message: ChatMessage,
        /** "Provider · model" that wrote an assistant reply this session; null otherwise. */
        val model: String?,
        /** True for the reply that just replaced the live stream, so it appears without a fade. */
        val replacedStream: Boolean
    ) : CoachRow {
        override val key: String get() = "m${message.id}"
    }

    data class Notice(val afterId: Long, val index: Int, val notice: CoachNotice) : CoachRow {
        override val key: String get() = "n${afterId}_$index"
    }

    data class Streaming(val text: String, val model: String) : CoachRow {
        override val key: String get() = "streaming"
    }

    data object Thinking : CoachRow {
        override val key: String get() = "thinking"
    }
}

data class CoachState(
    val loading: Boolean = true,
    val rows: List<CoachRow> = emptyList(),
    val hasMessages: Boolean = false,
    /** A request is in flight (waiting for the first token or streaming). */
    val busy: Boolean = false,
    val error: String? = null,
    val aiConfigured: Boolean = true,
    val modelLabel: String = "",
    val goalLabel: String = "",
    val haptics: Boolean = true
)

private enum class Phase { IDLE, WAITING, STREAMING, SETTLING }

/** Everything that is session-local (not in Room). */
private data class Local(
    val phase: Phase = Phase.IDLE,
    val streamingText: String = "",
    val streamingModel: String = "",
    /** Id of the assistant message that replaced the stream; hides the stream once it lands. */
    val settlingId: Long? = null,
    val error: String? = null,
    val notices: Map<Long, List<CoachNotice>> = emptyMap(),
    val models: Map<Long, String> = emptyMap()
)

class CoachViewModel(private val repo: ForgeRepository) : ViewModel() {

    private val local = MutableStateFlow(Local())
    private var job: Job? = null

    val state: StateFlow<CoachState> = combine(
        repo.chatDao.observeAll(),
        local,
        repo.settingsStore.settings
    ) { messages, l, settings ->
        val settled = l.phase == Phase.SETTLING && l.settlingId != null &&
            messages.any { it.id == l.settlingId }
        val rows = ArrayList<CoachRow>(messages.size + 2)
        messages.forEach { m ->
            rows += CoachRow.Message(
                message = m,
                model = l.models[m.id],
                replacedStream = m.id == l.settlingId
            )
            l.notices[m.id]?.forEachIndexed { i, n -> rows += CoachRow.Notice(m.id, i, n) }
        }
        when (l.phase) {
            Phase.WAITING -> rows += CoachRow.Thinking
            Phase.STREAMING -> rows += CoachRow.Streaming(l.streamingText, l.streamingModel)
            // Keep the streamed bubble until Room delivers the saved copy, so the list never
            // blinks empty between the two.
            Phase.SETTLING -> if (!settled) rows += CoachRow.Streaming(l.streamingText, l.streamingModel)
            Phase.IDLE -> Unit
        }
        val configured = runCatching { repo.ai.isConfigured }.getOrDefault(false) || settings.hasApiKey
        CoachState(
            loading = false,
            rows = rows,
            hasMessages = messages.isNotEmpty(),
            busy = l.phase == Phase.WAITING || l.phase == Phase.STREAMING,
            error = l.error,
            aiConfigured = configured,
            modelLabel = if (configured) runCatching { repo.ai.describe(Task.COACH) }.getOrDefault("") else "",
            goalLabel = settings.goal.label,
            haptics = settings.haptics
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoachState())

    fun dismissError() = local.update { it.copy(error = null) }

    fun clearHistory() {
        job?.cancel()
        viewModelScope.launch {
            repo.chatDao.clear()
            local.value = Local()
        }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isBlank()) return
        val phase = local.value.phase
        if (phase == Phase.WAITING || phase == Phase.STREAMING) return

        // The crisis screen runs on the raw text before anything else, so the help card never
        // depends on a model, a key or the network.
        val crisis = Guardrails.detectCrisis(message)
        val medical = Guardrails.detectMedicalRedFlags(message)
        val pain = if (medical == null) Guardrails.detectPainStop(message) else null

        local.update { it.copy(phase = Phase.WAITING, error = null, streamingText = "", settlingId = null) }

        job = viewModelScope.launch {
            try {
                val settings = repo.settings()
                val configured = runCatching { repo.ai.isConfigured }.getOrDefault(false) || settings.hasApiKey
                val hasNotice = crisis || medical != null || pain != null
                if (!configured && !hasNotice) {
                    local.update {
                        it.copy(phase = Phase.IDLE, error = "Set up an AI provider in Settings to use the coach.")
                    }
                    return@launch
                }

                val id = repo.chatDao.insert(
                    ChatMessage(role = "user", content = message, timestamp = System.currentTimeMillis())
                )

                var lockApplied = false
                if (crisis) {
                    // safety_crisis_disclosure applies the same 30-day locks as a positive
                    // SCOFF. The plan feature keeps that lock as crisisFlaggedAt (the words
                    // themselves are never stored), so the coach records it the same way.
                    runCatching {
                        PlanProfileStore.update { it.copy(crisisFlaggedAt = System.currentTimeMillis()) }
                    }.onSuccess { lockApplied = true }
                }
                val notices = buildList {
                    if (crisis) add(CoachNotice.Crisis(lockApplied || settings.edScreenPositive))
                    medical?.let { add(CoachNotice.Safety(it, emergency = it.id.endsWith(":emergency"))) }
                    pain?.let { add(CoachNotice.Safety(it, emergency = false)) }
                }
                if (notices.isNotEmpty()) {
                    local.update { it.copy(notices = it.notices + (id to notices)) }
                }

                if (!configured) {
                    local.update {
                        it.copy(phase = Phase.IDLE, error = "Set up an AI provider in Settings for the coach to reply.")
                    }
                    return@launch
                }

                val snapshot = runCatching { CoachContext.build(repo) }.getOrNull()
                val system = systemPrompt(snapshot, crisis, medical ?: pain)
                val history = history()
                val model = runCatching { repo.ai.describe(Task.COACH) }.getOrDefault("")

                val builder = StringBuilder()
                try {
                    repo.ai.chatStream(system, history, message).collect { delta ->
                        builder.append(delta)
                        val shown = Guardrails.neutralizeLanguage(builder.toString())
                        local.update { it.copy(phase = Phase.STREAMING, streamingText = shown, streamingModel = model) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep whatever streamed before the failure rather than losing it.
                    val partial = Guardrails.neutralizeLanguage(builder.toString()).trim()
                    val savedId = if (partial.isNotEmpty()) saveReply(partial) else null
                    local.update {
                        it.copy(
                            phase = if (savedId != null) Phase.SETTLING else Phase.IDLE,
                            settlingId = savedId,
                            models = if (savedId != null) it.models + (savedId to model) else it.models,
                            error = e.message ?: "The coach couldn't reply."
                        )
                    }
                    return@launch
                }

                val reply = Guardrails.neutralizeLanguage(builder.toString()).trim()
                if (reply.isEmpty()) {
                    local.update { it.copy(phase = Phase.IDLE, error = "The coach returned an empty reply. Try again.") }
                    return@launch
                }
                val savedId = saveReply(reply)
                local.update {
                    it.copy(
                        phase = Phase.SETTLING,
                        streamingText = reply,
                        settlingId = savedId,
                        models = it.models + (savedId to model)
                    )
                }
            } catch (e: CancellationException) {
                local.update { it.copy(phase = Phase.IDLE) }
                throw e
            } catch (e: Exception) {
                local.update { it.copy(phase = Phase.IDLE, error = e.message ?: "The coach couldn't reply.") }
            }
        }
    }

    private suspend fun saveReply(text: String): Long = repo.chatDao.insert(
        ChatMessage(role = "assistant", content = text, timestamp = System.currentTimeMillis())
    )

    /**
     * Previous turns, oldest first, excluding the message just sent. Consecutive turns from
     * the same role (e.g. a reply that failed) are merged, and the list starts with a user
     * turn, because chat APIs reject anything else.
     */
    private suspend fun history(): List<Pair<String, String>> {
        val turns = repo.chatDao.lastN(MAX_HISTORY + 1)
            .sortedBy { it.timestamp }
            .dropLast(1)
            .map { it.role to it.content }
            .dropWhile { it.first != "user" }
        val merged = ArrayList<Pair<String, String>>()
        turns.forEach { (role, content) ->
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                merged[merged.lastIndex] = role to (last.second + "\n\n" + content)
            } else {
                merged += role to content
            }
        }
        // The new user message follows, so history must end on an assistant turn.
        return if (merged.lastOrNull()?.first == "user") merged.dropLast(1) else merged
    }

    /**
     * Stable parts first (persona, evidence, source hierarchy) so a provider-side prompt cache
     * can reuse them; the person's rules and data come last because they change.
     * A crisis turn drops the evidence and the numbers entirely: the reply must be support,
     * not advice.
     */
    private fun systemPrompt(snapshot: CoachSnapshot?, crisis: Boolean, safety: GuardrailFlag?): String =
        buildString {
            append(KnowledgeBase.COACH_PERSONA)
            append("\n\n")
            if (crisis) {
                append(Guardrails.promptRules(snapshot?.profile))
                append("\n\n")
                append(CRISIS_TURN)
                return@buildString
            }
            append(KnowledgeBase.TRAINING_AND_NUTRITION)
            append("\n\n")
            append(KnowledgeBase.SOURCE_TRUST)
            append("\n\n")
            append(Guardrails.promptRules(snapshot?.profile))
            append("\n\n")
            append(snapshot?.context ?: "<user_data>\nThe app could not load this person's data for this turn. Say so if the answer depends on it.\n</user_data>")
            if (safety != null) {
                append("\n\n")
                append(
                    "<this_turn>The app has already shown this safety note under the user's message: \"${safety.message}\" " +
                        "Do not contradict it. Do not suggest training through these symptoms or diagnose; " +
                        "acknowledge it briefly, then answer anything else they asked within it.</this_turn>"
                )
            }
        }

    companion object {
        val Factory = forgeFactory { CoachViewModel(it) }

        private const val MAX_HISTORY = 20

        val QUICK_PROMPTS = listOf(
            "Am I on track?",
            "Plan my next session",
            "Why is my weight up?",
            "How much protein today?"
        )

        private val CRISIS_TURN = """
            <this_turn>
            The person's last message may mention self-harm, suicidal thoughts, making themselves sick, or misusing laxatives, diet pills or diuretics. Forge has already shown them a card with Tele MANAS (14416, toll-free 1800-89-14416, free and 24x7) and 112 for immediate danger, and has paused weight-loss features.
            Reply briefly and warmly, in plain words. Do not give any nutrition, calorie, weight or training advice in this reply, even if asked. Do not quote or repeat their words back, and do not diagnose. Acknowledge that things sound hard, say they don't have to handle this alone, encourage them to call Tele MANAS or talk to a doctor or someone they trust, and say to call 112 if they are in immediate danger. Invite them to keep talking.
            </this_turn>
        """.trimIndent()
    }
}
