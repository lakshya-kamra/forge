package com.laksh.forge.data.ai

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class ModelRouterTest {

    private fun pick(
        provider: ProviderSpec,
        tier: CostTier,
        task: Task,
        overrides: Map<String, String> = emptyMap(),
        vision: Boolean = task.visionByDefault
    ) = ModelRouter.pick(provider, tier, task, overrides, vision)

    @Test fun anthropicMatchesTheOriginalTierPolicy() {
        val a = ProviderRegistry.anthropic
        Task.entries.forEach { t ->
            assertThat(pick(a, CostTier.ECONOMY, t).model).isEqualTo("claude-haiku-5-5")
        }
        assertThat(pick(a, CostTier.BALANCED, Task.FOOD_PHOTO).model).isEqualTo("claude-sonnet-5-5")
        assertThat(pick(a, CostTier.BALANCED, Task.LABEL).model).isEqualTo("claude-haiku-5-5")
        assertThat(pick(a, CostTier.MAXIMUM, Task.COACH).model).isEqualTo("claude-opus-5-5")
        assertThat(pick(a, CostTier.MAXIMUM, Task.PING).model).isEqualTo("claude-haiku-5-5")
        // CostTier.modelFor (used by v1 screens) agrees with the router.
        assertThat(CostTier.MAXIMUM.modelFor(Task.PLAN)).isEqualTo(CostTier.OPUS)
    }

    @Test fun groqUsesItsOnlyVisionModelForPhotosAtEveryTier() {
        val g = ProviderRegistry.groq
        CostTier.entries.forEach { tier ->
            assertThat(pick(g, tier, Task.FOOD_PHOTO).model).isEqualTo("qwen/qwen3.8-27b")
            assertThat(pick(g, tier, Task.LABEL).model).isEqualTo("qwen/qwen3.8-27b")
        }
        assertThat(pick(g, CostTier.ECONOMY, Task.COACH).model).isEqualTo("openai/gpt-oss-20b")
        assertThat(pick(g, CostTier.BALANCED, Task.COACH).model).isEqualTo("openai/gpt-oss-120b")
    }

    @Test fun scannedPdfFallsBackToAVisionModel() {
        val m = pick(ProviderRegistry.deepseek, CostTier.MAXIMUM, Task.PROGRAM, vision = true)
        assertThat(m.model).isEqualTo("deepseek-flash")
        val text = pick(ProviderRegistry.deepseek, CostTier.MAXIMUM, Task.PROGRAM, vision = false)
        assertThat(text.model).isEqualTo("deepseek-v4-pro")
    }

    @Test fun labelsUseTheCheapestLabelModel() {
        assertThat(pick(ProviderRegistry.gemini, CostTier.MAXIMUM, Task.LABEL).model).isEqualTo("gemini-3.1-flash-lite")
        assertThat(pick(ProviderRegistry.openai, CostTier.MAXIMUM, Task.LABEL).model).isEqualTo("gpt-6-luna")
    }

    @Test fun maxTierPrefersTheJobSpecificModel() {
        assertThat(pick(ProviderRegistry.openai, CostTier.MAXIMUM, Task.COACH).model).isEqualTo("gpt-6.2-sol")
        assertThat(pick(ProviderRegistry.openai, CostTier.MAXIMUM, Task.FOOD_PHOTO).model).isEqualTo("gpt-6.2-astra")
    }

    @Test fun balancedFallsBackToEconomyBeforeMax() {
        // DeepSeek lists no balanced model.
        assertThat(pick(ProviderRegistry.deepseek, CostTier.BALANCED, Task.COACH).model).isEqualTo("deepseek-flash")
        // Mistral has no balanced coach model.
        assertThat(pick(ProviderRegistry.mistral, CostTier.BALANCED, Task.COACH).model).isEqualTo("mistral-small-2603")
    }

    @Test fun overridesWinAndDefaultAppliesToEveryTask() {
        val o = mapOf(Task.COACH.name to "gpt-6.1-terra", ModelOverrides.DEFAULT_KEY to "gpt-6-luna")
        val coach = pick(ProviderRegistry.openai, CostTier.MAXIMUM, Task.COACH, o)
        assertThat(coach.model).isEqualTo("gpt-6.1-terra")
        assertThat(coach.overridden).isTrue()
        assertThat(pick(ProviderRegistry.openai, CostTier.MAXIMUM, Task.PLAN, o).model).isEqualTo("gpt-6-luna")
    }

    @Test fun textOnlyOverrideIsIgnoredForPhotos() {
        val o = mapOf(Task.FOOD_PHOTO.name to "openai/gpt-oss-120b")
        val m = pick(ProviderRegistry.groq, CostTier.BALANCED, Task.FOOD_PHOTO, o)
        assertThat(m.model).isEqualTo("qwen/qwen3.8-27b")
        assertThat(m.note).isNotNull()
    }

    @Test fun customProviderNeedsAModelChoice() {
        assertThrows(AiException::class.java) {
            pick(ProviderRegistry.custom, CostTier.BALANCED, Task.COACH)
        }
        val m = pick(ProviderRegistry.custom, CostTier.BALANCED, Task.COACH, mapOf(ModelOverrides.DEFAULT_KEY to "llama-x"))
        assertThat(m.model).isEqualTo("llama-x")
        assertThat(m.spec).isNull()
    }

    @Test fun overridesAreScopedToTheirProvider() {
        val json = ModelOverrides.serialize("openai", mapOf("COACH" to "gpt-6.1-terra"))
        assertThat(ModelOverrides.parse(json, "openai")).containsExactly("COACH", "gpt-6.1-terra")
        assertThat(ModelOverrides.parse(json, "groq")).isEmpty()
        assertThat(ModelOverrides.parse("not json", "openai")).isEmpty()
        assertThat(ModelOverrides.serialize("openai", emptyMap())).isEmpty()
    }

    @Test fun everyRegistryProviderExceptCustomCanRouteEveryTask() {
        ProviderRegistry.all.filterNot { it.isCustom }.forEach { p ->
            CostTier.entries.forEach { tier ->
                Task.entries.forEach { task ->
                    val m = pick(p, tier, task)
                    if (task.visionByDefault) assertThat(m.spec?.vision).isTrue()
                }
            }
        }
    }

    @Test fun costEstimateIsOrderedByTierAndTracksPriceChanges() {
        val a = ProviderRegistry.anthropic
        val day = LocalDate.of(2026, 11, 17)
        val eco = CostEstimator.monthly(a, CostTier.ECONOMY, emptyMap(), day).totalUsd!!
        val bal = CostEstimator.monthly(a, CostTier.BALANCED, emptyMap(), day).totalUsd!!
        val max = CostEstimator.monthly(a, CostTier.MAXIMUM, emptyMap(), day).totalUsd!!
        assertThat(eco).isLessThan(bal)
        assertThat(bal).isLessThan(max)

        val carbon = ProviderRegistry.gemini.model("gemini-4-carbon")!!
        assertThat(carbon.priceOn(day)).isEqualTo(2.00 to 10.00)
        assertThat(carbon.priceOn(LocalDate.of(2026, 12, 1))).isEqualTo(4.00 to 20.00)
    }

    @Test fun unknownModelsHaveNoTotal() {
        val e = CostEstimator.monthly(
            ProviderRegistry.custom, CostTier.BALANCED,
            mapOf(ModelOverrides.DEFAULT_KEY to "my-model"), LocalDate.of(2026, 11, 17)
        )
        assertThat(e.totalUsd).isNull()
        assertThat(e.unpricedModels).containsExactly("my-model")
    }

    @Test fun baseUrlNormalisation() {
        assertThat(AiHttp.normalizeBaseUrl("https://api.example.com/v1/")).isEqualTo("https://api.example.com/v1")
        assertThat(AiHttp.normalizeBaseUrl("api.example.com/v1/chat/completions")).isEqualTo("https://api.example.com/v1")
        assertThat(AiHttp.normalizeBaseUrl("http://192.168.1.5:11434/v1")).isNull()
        assertThat(AiHttp.normalizeBaseUrl("")).isNull()
        assertThat(AiHttp.joinUrl("https://x.io/v1beta/openai/", "chat/completions"))
            .isEqualTo("https://x.io/v1beta/openai/chat/completions")
        assertThat(AiHttp.joinUrl("https://api.deepseek.com", "/models")).isEqualTo("https://api.deepseek.com/models")
    }
}
