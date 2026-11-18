package com.laksh.forge.domain.guardrails

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CoachReplyFilterTest {

    @Test
    fun praiseForRestrictionAndDietCultureWordsAreCaught() {
        val reply = "Great job staying under 1,000 kcal yesterday, earn your food with cardio, no guilt"
        assertThat(Guardrails.unsafeReply(reply, 1200)).isTrue()
        assertThat(Guardrails.unsafeReply("Well done, you skipped breakfast again!", null)).isTrue()
        assertThat(Guardrails.unsafeReply("Have a pizza and burn it off tomorrow.", null)).isTrue()
    }

    @Test
    fun aDailyIntakeBelowTheFloorIsCaughtButSnacksAreNot() {
        assertThat(Guardrails.unsafeReply("Try eating 900 kcal a day for a week.", 1200)).isTrue()
        assertThat(Guardrails.unsafeReply("A 300 kcal snack before training works well.", 1200)).isFalse()
        assertThat(Guardrails.unsafeReply("Your target is 2,100 kcal a day.", 1200)).isFalse()
    }

    @Test
    fun ordinaryCoachingPasses() {
        assertThat(
            Guardrails.unsafeReply("You're on track. Keep protein near 150 g and add one set to rows next week.", 1500)
        ).isFalse()
    }
}
