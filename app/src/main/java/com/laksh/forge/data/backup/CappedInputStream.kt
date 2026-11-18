package com.laksh.forge.data.backup

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Fails as soon as more than [limit] bytes have been read, so a huge wrong file is refused
 * after at most [limit] bytes instead of after it has been buffered into memory.
 */
internal class CappedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {

    private var total = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(n: Long) {
        total += n
        if (total > limit) throw IOException(TOO_LARGE)
    }

    companion object {
        const val TOO_LARGE = "That file is too large to import."
    }
}
