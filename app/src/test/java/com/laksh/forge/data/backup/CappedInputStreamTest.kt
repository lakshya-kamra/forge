package com.laksh.forge.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class CappedInputStreamTest {

    /** An endless stream that counts what was handed out, so the test can see where reading stopped. */
    private class Endless : InputStream() {
        var served = 0L
        override fun read(): Int { served++; return 'a'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            b.fill('a'.code.toByte(), off, off + len); served += len; return len
        }
    }

    @Test
    fun `a file within the cap reads fully`() {
        val bytes = CappedInputStream("hello".byteInputStream(), 5).use { it.readBytes() }
        assertThat(String(bytes)).isEqualTo("hello")
    }

    @Test
    fun `an oversized stream fails near the cap instead of buffering it all`() {
        val src = Endless()
        val e = assertThrows(IOException::class.java) {
            CappedInputStream(src, 1_000).use { it.readBytes() }
        }
        assertThat(e.message).isEqualTo(CappedInputStream.TOO_LARGE)
        assertThat(src.served).isLessThan(64L * 1024)
    }
}
