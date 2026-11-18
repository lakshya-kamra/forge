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
import com.laksh.forge.data.ai.AiRefusal
import com.laksh.forge.data.prefs.GoalMode
import com.laksh.forge.domain.plan.PhaseCopy
import com.laksh.forge.domain.plan.PlanProfileStore
import com.laksh.forge.domain.plan.PlanStore
import com.laksh.forge.ui.forgeFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
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
                    // safety_crisis_disclosure applies the same locks as a positive SCOFF: the
                    // screen flag (weigh-in reminders, hidden numbers, body-fat views, the
                    // no-plan targets) stays on until a negative re-screen after 30 days, any
                    // deficit stops now, and the plan moves to maintenance. The words themselves
                    // are never stored beyond the chat history the person can clear.
                    lockApplied = runCatching { applyCrisisLocks() }.isSuccess
                }
                if (medical != null) {
                    // safety_medical_red_flags_stop: Train and Session stay locked until the
                    // person confirms a doctor has cleared them.
                    runCatching { PlanProfileStore.holdTraining() }
                }
                val notices = buildList {
                    if (crisis) add(CoachNotice.Crisis(lockApplied || settings.edScreenPositive))
                    medical?.let { add(CoachNotice.Safety(it, emergency = it.id.endsWith(":emergency"))) }
                    pain?.let { add(CoachNotice.Safety(it, emergency = false)) }
                }
                if (notices.isNotEmpty()) {
                    local.update { it.copy(notices = it.notices + (id to notices)) }
                }

                if (crisis) {
                    // No coaching output on a crisis turn: the card plus a fixed supportive
                    // message, never a model reply that could carry numbers or advice.
                    val savedId = saveReply(Guardrails.CRISIS_MESSAGE)
                    local.update {
                        it.copy(phase = Phase.SETTLING, streamingText = Guardrails.CRISIS_MESSAGE, settlingId = savedId)
                    }
                    return@launch
                }

                if (!configured) {
                    local.update {
                        it.copy(phase = Phase.IDLE, error = "Set up an AI provider in Settings for the coach to reply.")
                    }
                    return@launch
                }

                val snapshot = runCatching { CoachContext.build(repo) }.getOrNull()
                val system = systemPrompt(snapshot, false, medical ?: pain)
                val history = history()
                val model = runCatching { repo.ai.describe(Task.COACH) }.getOrDefault("")
                val floor = snapshot?.profile?.let { runCatching { Guardrails.calorieFloor(it) }.getOrNull() }

                val builder = StringBuilder()
                var unsafe = false
                try {
                    repo.ai.chatStream(system, history, message)
                        // Stops the stream (and billing) as soon as the reply fails the filter.
                        .takeWhile { !unsafe }
                        .collect { delta ->
                            builder.append(delta)
                            val shown = Guardrails.neutralizeLanguage(builder.toString())
                            if (Guardrails.unsafeReply(shown, floor)) {
                                unsafe = true
                                local.update { it.copy(phase = Phase.WAITING, streamingText = "") }
                            } else {
                                local.update { it.copy(phase = Phase.STREAMING, streamingText = shown, streamingModel = model) }
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A refusal's partial text must be discarded; other failures keep whatever
                    // streamed (if it passed the filter) rather than losing it.
                    val partial = Guardrails.neutralizeLanguage(builder.toString()).trim()
                    val keep = e !is AiRefusal && !unsafe && partial.isNotEmpty() && !Guardrails.unsafeReply(partial, floor)
                    val savedId = if (keep) saveReply(partial) else null
                    local.update {
                        it.copy(
                            phase = if (savedId != null) Phase.SETTLING else Phase.IDLE,
                            settlingId = savedId,
                            streamingText = if (savedId != null) partial else "",
                            models = if (savedId != null) it.models + (savedId to model) else it.models,
                            error = e.message ?: "The coach couldn't reply."
                        )
                    }
                    return@launch
                }

                var reply = Guardrails.neutralizeLanguage(builder.toString()).trim()
                if (unsafe || Guardrails.unsafeReply(reply, floor)) {
                    reply = regenerateSafely(system, history, message, floor)
                }
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

    /** Same locks as CheckinViewModel.persist for a crisis or positive screen. */
    private suspend fun applyCrisisLocks() {
        val now = System.currentTimeMillis()
        PlanProfileStore.update { it.copy(crisisFlaggedAt = now) }
        repo.settingsStore.setEdScreen(true, now)
        val plan = PlanStore.planDao.activePlan()
        if (plan != null) {
            if (PhaseCopy.isDeficit(plan.phase)) {
                PlanStore.switchToMaintenance(
                    repo,
                    "Forge has switched to maintenance and paused weight-loss features for now. Training stays the focus."
                )
            }
        } else {
            // Without a plan, Today falls back to the legacy goal and rate.
            val s = repo.settings()
            if (s.goal == GoalMode.CUT || s.rateKgPerWeek < 0) {
                repo.settingsStore.setGoal(GoalMode.MAINTAIN)
                repo.settingsStore.setRate(0.0)
            }
        }
    }

    /**
     * One silent retry with an explicit reminder, then a neutral fallback, for a reply that
     * failed the post-generation filter (safety_messaging_no_praise_for_restriction).
     */
    private suspend fun regenerateSafely(
        system: String,
        history: List<Pair<String, String>>,
        message: String,
        floor: Int?
    ): String {
        val retry = runCatching {
            val b = StringBuilder()
            repo.ai.chatStream(system + "\n\n" + SAFE_RETRY, history, message).collect { b.append(it) }
            Guardrails.neutralizeLanguage(b.toString()).trim()
        }.getOrNull()
        return if (retry != null && retry.isNotEmpty() && !Guardrails.unsafeReply(retry, floor)) retry else SAFE_FALLBACK
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
        val raw = repo.chatDao.lastN(MAX_HISTORY + 1)
            .sortedBy { it.timestamp }
            .dropLast(1)
            .map { it.role to it.content }
        // A crisis turn and its fixed reply are never sent to a provider again.
        val kept = ArrayList<Pair<String, String>>()
        var skipReply = false
        raw.forEach { turn ->
            when {
                turn.first == "user" && Guardrails.detectCrisis(turn.second) -> skipReply = true
                skipReply && turn.first == "assistant" -> skipReply = false
                else -> { skipReply = false; kept += turn }
            }
        }
        val turns = kept.dropWhile { it.first != "user" }
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

        private const val SAFE_RETRY =
            "<this_turn>Your previous draft was withheld by Forge's safety filter. Do not praise eating little, " +
                "skipping meals or staying under a number; do not use the words guilt, earn, burn it off, clean food, " +
                "cheat, junk or any body-shaming term; never suggest a daily intake below the calorie floor.</this_turn>"

        private const val SAFE_FALLBACK =
            "I'd rather not put it the way my first draft did. Food is fuel for your training, not something to earn " +
                "or make up for, and your daily intake should stay at or above your calorie floor. Ask me again and " +
                "I'll keep it practical."

        private val CRISIS_TURN = """
            <this_turn>
            The person's last message may mention self-harm, suicidal thoughts, making themselves sick, or misusing laxatives, diet pills or diuretics. Forge has already shown them a card with Tele MANAS (14416, toll-free 1800-89-14416, free and 24x7) and 112 for immediate danger, and has paused weight-loss features.
            Reply briefly and warmly, in plain words. Do not give any nutrition, calorie, weight or training advice in this reply, even if asked. Do not quote or repeat their words back, and do not diagnose. Acknowledge that things sound hard, say they don't have to handle this alone, encourage them to call Tele MANAS or talk to a doctor or someone they trust, and say to call 112 if they are in immediate danger. Invite them to keep talking.
            </this_turn>
        """.trimIndent()
    }
}
