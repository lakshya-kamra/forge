package com.laksh.forge.data.ai

import com.google.common.truth.Truth.assertThat
import com.laksh.forge.data.remote.CostTier
import com.laksh.forge.data.remote.Task
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okio.Buffer
import org.junit.Test
import java.time.LocalDate

/** Command Code's two wires and OpenRouter's Ultra-cheap preset, checked on the built requests. */
class ProviderRoutingTest {

    private val cc = ProviderRegistry.commandCode
    private val or = ProviderRegistry.openrouter

    private val schema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { putJsonObject("name") { put("type", "string") } }
        putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("name")) }
        put("additionalProperties", false)
    }

    private fun request(provider: ProviderSpec, task: Task, withImage: Boolean, json: Boolean): AiRequest {
        val pick = ModelRouter.pick(provider, CostTier.BALANCED, task, emptyMap(), needsVision = withImage)
        val parts = buildList<AiPart> {
            if (withImage) add(AiPart.Image("AAAA", "image/jpeg"))
            add(AiPart.Text("hello"))
        }
        return AiRequest(
            task = task,
            model = pick.model,
            modelSpec = pick.spec,
            system = "system prompt",
            turns = listOf(AiTurn.user(parts)),
            maxTokens = 1000,
            json = if (json) JsonSpec("thing", schema) else null
        )
    }

    private fun Request.bodyJson(): JsonObject {
        val buffer = Buffer()
        body!!.writeTo(buffer)
        return AiJson.json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    // ---------------------------------------------------------------- Command Code

    @Test fun commandCodeSendsVisionJobsAndCoachToSonnetOnMessages() {
        listOf(Task.FOOD_PHOTO, Task.LABEL, Task.BODY).forEach { task ->
            val r = CommandCodeProvider(cc).buildRequest("cc-key", request(cc, task, withImage = true, json = true))
            assertThat(r.url.toString()).isEqualTo("https://api.commandcode.ai/provider/v1/messages")
            assertThat(r.bodyJson()["model"]!!.jsonPrimitive.content).isEqualTo("claude-sonnet-4-6")
        }
        val coach = request(cc, Task.COACH, withImage = false, json = false)
        assertThat(coach.model).isEqualTo("claude-sonnet-4-6")
        assertThat(CommandCodeProvider(cc).buildRequest("cc-key", coach, stream = true).url.encodedPath)
            .isEqualTo("/provider/v1/messages")
    }

    @Test fun commandCodeSendsPlanAndProgramToDeepSeekOnChatCompletions() {
        listOf(Task.PLAN, Task.PROGRAM).forEach { task ->
            val req = request(cc, task, withImage = false, json = true)
            assertThat(req.model).isEqualTo("deepseek/deepseek-v4-flash")
            val r = CommandCodeProvider(cc).buildRequest("cc-key", req)
            assertThat(r.url.toString()).isEqualTo("https://api.commandcode.ai/provider/v1/chat/completions")
        }
    }

    @Test fun everyCommandCodeRequestCarriesZdrAndBearerAuth() {
        val provider = CommandCodeProvider(cc)
        val requests = listOf(
            provider.buildRequest("cc-key", request(cc, Task.FOOD_PHOTO, withImage = true, json = true)),
            provider.buildRequest("cc-key", request(cc, Task.COACH, withImage = false, json = false), stream = true),
            provider.buildRequest("cc-key", request(cc, Task.PLAN, withImage = false, json = true))
        )
        requests.forEach { r ->
            assertThat(r.header("x-cmd-zdr")).isEqualTo("1")
            assertThat(r.header("Authorization")).isEqualTo("Bearer cc-key")
            assertThat(r.header("x-api-key")).isNull()
        }
    }

    @Test fun overrideIdsPickTheirWireByName() {
        assertThat(CommandCodeProvider.usesMessages("claude-opus-4-6", null)).isTrue()
        assertThat(CommandCodeProvider.usesMessages("moonshotai/kimi-k3", null)).isFalse()
        val spec = cc.model("deepseek/deepseek-v4-flash")
        assertThat(CommandCodeProvider.usesMessages("deepseek/deepseek-v4-flash", spec)).isFalse()
    }

    @Test fun scannedProgramOnCommandCodeFallsBackToTheVisionModel() {
        val pick = ModelRouter.pick(cc, CostTier.ECONOMY, Task.PROGRAM, emptyMap(), needsVision = true)
        assertThat(pick.model).isEqualTo("claude-sonnet-4-6")
    }

    @Test fun commandCodeRoutingIsTheSameAtEveryTier() {
        CostTier.entries.forEach { tier ->
            assertThat(ModelRouter.pick(cc, tier, Task.PLAN, emptyMap(), false).model).isEqualTo("deepseek/deepseek-v4-flash")
            assertThat(ModelRouter.pick(cc, tier, Task.COACH, emptyMap(), false).model).isEqualTo("claude-sonnet-4-6")
        }
        assertThat(ModelRouter.tiersFor(cc)).isEmpty()
    }

    @Test fun leanMessagesRetryDropsOutputConfigAndMovesTheSchemaIntoThePrompt() {
        val anthropicWire = AnthropicProvider(
            spec = cc, baseUrl = cc.baseUrl, messagesPath = "/messages", bearerAuth = true, gateway = true
        )
        val req = request(cc, Task.LABEL, withImage = true, json = true)
        val full = anthropicWire.buildRequest("k", req).bodyJson()
        assertThat(full.containsKey("output_config")).isTrue()
        val lean = anthropicWire.buildRequest("k", req, lean = true).bodyJson()
        assertThat(lean.containsKey("output_config")).isFalse()
        assertThat(lean.toString()).contains("OUTPUT FORMAT")
    }

    @Test fun commandCodeErrorCodesBecomeActionableMessages() {
        val zdr = AiHttp.errorFor(
            "Command Code", 422,
            """{"error":{"type":"invalid_request_error","code":"cmd_zdr_no_providers","message":"no ZDR upstream"}}"""
        )
        assertThat(zdr.errorCode).isEqualTo(AiHttp.ZDR_NO_PROVIDERS)
        assertThat(zdr.message).contains("Pick another model")

        val credits = AiHttp.errorFor("Command Code", 403, """{"error":{"type":"upgrade_required","message":"x"}}""")
        assertThat(credits.errorCode).isEqualTo(AiHttp.UPGRADE_REQUIRED)
        assertThat(credits.message).contains("credits")
    }

    @Test fun commandCodeIsNeverAutoDetected() {
        // No documented prefix, so an arbitrary key is Unknown and the UI asks the user to pick.
        assertThat(KeyDetector.detect("cmd_4f8a2b9c0d1e2f3a4b5c6d7e8f9a0b1c")).isInstanceOf(KeyDetection.Unknown::class.java)
        assertThat(ProviderRegistry.all).contains(cc)
    }

    // ---------------------------------------------------------------- OpenRouter Ultra-cheap

    @Test fun ultraCheapPresetRoutesEachJobToTheResearchedModel() {
        listOf(Task.FOOD_PHOTO, Task.LABEL, Task.BODY).forEach { task ->
            val m = ModelRouter.pickFor(or, ModelRouter.ULTRA_CHEAP, task, emptyMap(), needsVision = true)
            assertThat(m.model).isEqualTo("google/gemma-4-31b-it")
            assertThat(m.spec?.vision).isTrue()
        }
        listOf(Task.COACH, Task.PLAN, Task.PROGRAM).forEach { task ->
            val m = ModelRouter.pickFor(or, ModelRouter.ULTRA_CHEAP, task, emptyMap(), needsVision = false)
            assertThat(m.model).isEqualTo("anthropic/claude-haiku-5.5")
        }
        // A scanned PDF still goes to Haiku, which reads images.
        assertThat(ModelRouter.pickFor(or, ModelRouter.ULTRA_CHEAP, Task.PROGRAM, emptyMap(), true).model)
            .isEqualTo("anthropic/claude-haiku-5.5")
    }

    @Test fun overridesStillBeatThePreset() {
        val m = ModelRouter.pickFor(or, ModelRouter.ULTRA_CHEAP, Task.COACH, mapOf("COACH" to "openai/gpt-6.1-terra"), false)
        assertThat(m.model).isEqualTo("openai/gpt-6.1-terra")
        assertThat(m.overridden).isTrue()
    }

    @Test fun addingThePresetModelsLeavesOpenRouterEconomyUnchanged() {
        assertThat(ModelRouter.pick(or, CostTier.ECONOMY, Task.FOOD_PHOTO, emptyMap(), true).model).isEqualTo("openai/gpt-6-luna")
        assertThat(ModelRouter.pick(or, CostTier.ECONOMY, Task.COACH, emptyMap(), false).model).isEqualTo("openai/gpt-6-luna")
    }

    @Test fun ultraCheapIsOnlyOfferedWhereThereIsAPreset() {
        assertThat(ModelRouter.tiersFor(ProviderRegistry.anthropic).map { it.name }).doesNotContain(ModelRouter.ULTRA_CHEAP)
        assertThat(ModelRouter.defaultTierFor(ProviderRegistry.anthropic)).isNull()
        // Once CostTier.ULTRA_CHEAP exists it is OpenRouter's default and listed first.
        ModelRouter.ultraCheapTier?.let { ultra ->
            assertThat(ModelRouter.defaultTierFor(or)).isEqualTo(ultra)
            assertThat(ModelRouter.tiersFor(or).first()).isEqualTo(ultra)
            assertThat(ModelRouter.effectiveCostTier(ProviderRegistry.anthropic, ultra)).isEqualTo(CostTier.ECONOMY)
        }
    }

    @Test fun ultraCheapCostsLessThanEconomyAtTheNewUsage() {
        val day = LocalDate.of(2026, 11, 25)
        val ultra = CostEstimator.monthlyFor(or, ModelRouter.ULTRA_CHEAP, emptyMap(), day).totalUsd!!
        val economy = CostEstimator.monthly(or, CostTier.ECONOMY, emptyMap(), day).totalUsd!!
        assertThat(ultra).isLessThan(economy)
        assertThat(CostEstimator.PHOTOS_PER_DAY).isEqualTo(5)
        assertThat(CostEstimator.LABELS_PER_DAY).isEqualTo(3)
        assertThat(CostEstimator.COACH_PER_DAY).isEqualTo(3)
    }

    @Test fun everyOpenRouterRequestAsksForZeroDataRetention() {
        val provider = OpenAiCompatProvider(or, or.baseUrl)
        fun req(task: Task, image: Boolean, json: Boolean): AiRequest {
            val pick = ModelRouter.pickFor(or, ModelRouter.ULTRA_CHEAP, task, emptyMap(), image)
            return request(or, task, image, json).copy(model = pick.model, modelSpec = pick.spec)
        }

        val structured = provider.buildRequest("sk-or-v1-x", req(Task.FOOD_PHOTO, image = true, json = true)).bodyJson()
        val prefs = structured["provider"]!!.jsonObject
        assertThat(prefs["zdr"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(prefs["data_collection"]!!.jsonPrimitive.content).isEqualTo("deny")
        assertThat(prefs["require_parameters"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(structured["model"]!!.jsonPrimitive.content).isEqualTo("google/gemma-4-31b-it")

        // Streaming coach: no response_format, so no require_parameters, but ZDR all the same.
        val coach = provider.buildRequest("sk-or-v1-x", req(Task.COACH, image = false, json = false), stream = true).bodyJson()
        val coachPrefs = coach["provider"]!!.jsonObject
        assertThat(coachPrefs["zdr"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(coachPrefs["data_collection"]!!.jsonPrimitive.content).isEqualTo("deny")
        assertThat(coachPrefs.containsKey("require_parameters")).isFalse()
        assertThat(coach["model"]!!.jsonPrimitive.content).isEqualTo("anthropic/claude-haiku-5.5")
    }

    @Test fun otherProvidersGetNoOpenRouterPreferences() {
        val groq = ProviderRegistry.groq
        val body = OpenAiCompatProvider(groq, groq.baseUrl)
            .buildRequest("gsk_x", request(groq, Task.COACH, withImage = false, json = true)).bodyJson()
        assertThat(body.containsKey("provider")).isFalse()
    }
}
