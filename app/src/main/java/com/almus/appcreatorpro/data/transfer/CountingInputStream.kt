package com.almus.appcreatorpro.data.transfer

import java.io.FilterInputStream
import java.io.InputStream

/** Counts the bytes read through it (used for progress and the zip-bomb ratio check). */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile
    var count: Long = 0L
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n.toLong()
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count += skipped
        return skipped
    }

    override fun markSupported(): Boolean = false
}
