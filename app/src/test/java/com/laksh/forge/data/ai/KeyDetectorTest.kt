package com.laksh.forge.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyDetectorTest {

    private fun hex(n: Int) = "0123456789abcdef".repeat(n / 16 + 1).take(n)

    private fun match(key: String): KeyDetection.Match {
        val d = KeyDetector.detect(key)
        assertThat(d).isInstanceOf(KeyDetection.Match::class.java)
        return d as KeyDetection.Match
    }

    @Test fun emptyAndBlankAreEmpty() {
        assertThat(KeyDetector.detect("")).isEqualTo(KeyDetection.Empty)
        assertThat(KeyDetector.detect("   \n ")).isEqualTo(KeyDetection.Empty)
    }

    @Test fun smartQuotesAroundKeyAreStripped() {
        val m = match("“sk-ant-api03-AbCdEf_123-xyz”")
        assertThat(m.provider.id).isEqualTo("anthropic")
    }

    @Test fun nonAsciiKeyIsRejectedBeforeAnyNetworkCall() {
        // A truncated dashboard copy: OkHttp would throw IllegalArgumentException on the header.
        assertThat(KeyDetector.detect("sk-…abcd")).isInstanceOf(KeyDetection.Rejected::class.java)
        assertThat(KeyDetector.detect("AIza…")).isInstanceOf(KeyDetection.Rejected::class.java)
    }

    @Test fun anthropicKey() {
        val m = match("sk-ant-api03-AbCdEf_123-xyz")
        assertThat(m.provider.id).isEqualTo("anthropic")
        assertThat(m.confident).isTrue()
    }

    @Test fun openRouterIsCheckedBeforeGenericSk() {
        val m = match("sk-or-v1-" + hex(64))
        assertThat(m.provider.id).isEqualTo("openrouter")
        assertThat(m.confident).isTrue()
        assertThat(m.note).isNull()
    }

    @Test fun openRouterWithOddLengthStillDetectedButNoted() {
        val m = match("sk-or-v1-" + hex(40))
        assertThat(m.provider.id).isEqualTo("openrouter")
        assertThat(m.note).isNotNull()
    }

    @Test fun openAiProjectAndServiceAccountKeys() {
        assertThat(match("sk-proj-Abc123_def-456").provider.id).isEqualTo("openai")
        assertThat(match("sk-svcacct-Abc123").provider.id).isEqualTo("openai")
    }

    @Test fun openAiLegacyKeyWithMarker() {
        val m = match("sk-abcDEF123T3BlbkFJxyz789")
        assertThat(m.provider.id).isEqualTo("openai")
        assertThat(m.confident).isTrue()
    }

    @Test fun openAiAdminKeyIsRejected() {
        val d = KeyDetector.detect("sk-admin-Abc123")
        assertThat(d).isInstanceOf(KeyDetection.Rejected::class.java)
        assertThat((d as KeyDetection.Rejected).provider?.id).isEqualTo("openai")
        assertThat(d.reason).contains("admin")
    }

    @Test fun deepSeekShapeIsLowConfidence() {
        val m = match("sk-" + hex(32))
        assertThat(m.provider.id).isEqualTo("deepseek")
        assertThat(m.confident).isFalse()
    }

    @Test fun otherSkKeysAreAmbiguousBetweenOpenAiAndDeepSeek() {
        val d = KeyDetector.detect("sk-ZZZZ" + hex(30))
        assertThat(d).isInstanceOf(KeyDetection.Ambiguous::class.java)
        assertThat((d as KeyDetection.Ambiguous).candidates.map { it.id })
            .containsExactly("openai", "deepseek").inOrder()
    }

    @Test fun geminiStandardKey() {
        // AIza + 35 characters, the commonly observed Google key length.
        val m = match("AIzaSy" + hex(33))
        assertThat(m.provider.id).isEqualTo("gemini")
        assertThat(m.confident).isTrue()
    }

    @Test fun geminiKeyWithUnusualLengthIsLowConfidence() {
        val m = match("AIzaSyShort")
        assertThat(m.provider.id).isEqualTo("gemini")
        assertThat(m.confident).isFalse()
    }

    @Test fun geminiAuthKeyIsLowConfidence() {
        val m = match("AQ.Ab8RN6Kx-yz_1.2")
        assertThat(m.provider.id).isEqualTo("gemini")
        assertThat(m.confident).isFalse()
        assertThat(m.note).isNotNull()
    }

    @Test fun groqAndXai() {
        assertThat(match("gsk_AbC123xyz").provider.id).isEqualTo("groq")
        assertThat(match("xai-AbCdEfGhIjKl123").provider.id).isEqualTo("xai")
    }

    @Test fun unknownKeysAreProbedAgainstProvidersWithoutAFormat() {
        val d = KeyDetector.detect("Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56")
        assertThat(d).isInstanceOf(KeyDetection.Unknown::class.java)
        assertThat((d as KeyDetection.Unknown).probeCandidates.map { it.id })
            .containsExactly("mistral", "together")
    }

    @Test fun cleaningHandlesQuotesBearerEnvAndLineBreaks() {
        assertThat(KeyDetector.clean("  \"sk-ant-abc\"  ")).isEqualTo("sk-ant-abc")
        assertThat(KeyDetector.clean("Bearer sk-ant-abc")).isEqualTo("sk-ant-abc")
        assertThat(KeyDetector.clean("ANTHROPIC_API_KEY=sk-ant-abc")).isEqualTo("sk-ant-abc")
        assertThat(KeyDetector.clean("sk-ant-ab\ncd")).isEqualTo("sk-ant-abcd")
        assertThat(match("Bearer sk-ant-abc").provider.id).isEqualTo("anthropic")
    }

    @Test fun maskShowsOnlyPrefixAndLastFour() {
        val masked = KeyDetector.mask("sk-ant-api03-SECRETSECRET-wxyz")
        assertThat(masked).startsWith("sk-ant-")
        assertThat(masked).endsWith("wxyz")
        assertThat(masked).doesNotContain("SECRET")
    }
}
