package com.laksh.forge.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AiRequestPolicyTest {

    @Test
    fun effortIsOnlySentToModelsThatAcceptIt() {
        assertThat(AnthropicProvider.effortCapable("claude-opus-5-5")).isTrue()
        assertThat(AnthropicProvider.effortCapable("claude-sonnet-4-6")).isTrue()
        assertThat(AnthropicProvider.effortCapable("claude-opus-4-5-20251101")).isTrue()
        assertThat(AnthropicProvider.effortCapable("claude-sonnet-4-5")).isFalse()
        assertThat(AnthropicProvider.effortCapable("claude-haiku-4-5-20251001")).isFalse()
        assertThat(AnthropicProvider.effortCapable("claude-sonnet-4-20250514")).isFalse()
        assertThat(AnthropicProvider.effortCapable("claude-3-5-sonnet-latest")).isFalse()
    }

    @Test
    fun onlyFailuresBeforeTheRequestWasSentAreRetriedForGenerations() {
        assertThat(AiHttp.failedBeforeSend(UnknownHostException("api"))).isTrue()
        assertThat(AiHttp.failedBeforeSend(ConnectException("refused"))).isTrue()
        assertThat(AiHttp.failedBeforeSend(SocketTimeoutException("connect timed out"))).isTrue()
        // A read timeout on a long non-streaming generation: the server may still be billing it.
        assertThat(AiHttp.failedBeforeSend(SocketTimeoutException("timeout"))).isFalse()
        assertThat(AiHttp.failedBeforeSend(IOException("unexpected end of stream"))).isFalse()
    }

    @Test
    fun refusalsAreADistinctType() {
        val e: AiException = AiRefusal("declined")
        assertThat(e).isInstanceOf(AiRefusal::class.java)
    }
}
