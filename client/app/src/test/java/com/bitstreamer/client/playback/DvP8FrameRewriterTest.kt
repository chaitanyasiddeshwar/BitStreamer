package com.bitstreamer.client.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DvP8FrameRewriterTest {

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // NAL header byte 0 = type << 1
    private val vps = b(0, 0, 0, 1, 0x40, 0x01, 0xAA)
    private val slice = b(0, 0, 1, 0x02, 0x01, 0x11, 0x22, 0x33)
    private val el = b(0, 0, 1, 0x7E, 0x01, 0x55, 0x66)               // NAL 63
    private val rpu = b(0, 0, 1, 0x7C, 0x01, 0x19, 0x08, 0x80, 0, 0)  // NAL 62 + trailing zeros

    private val convertedRpu = b(0x7C, 0x01, 0x08, 0x80)

    @Test
    fun dropsElReplacesRpuNoFillerNormalisedStartCodes() {
        val au = vps + slice + el + rpu
        var seen: ByteArray? = null
        val rw = DvP8FrameRewriter { src, off, len -> seen = src.copyOfRange(off, off + len); convertedRpu }
        val n = rw.rewrite(au, au.size)
        // Converter got the RPU without start code or trailing zeros.
        assertArrayEquals(b(0x7C, 0x01, 0x19, 0x08, 0x80), seen)
        val expected = b(0, 0, 0, 1, 0x40, 0x01, 0xAA) +
            b(0, 0, 0, 1, 0x02, 0x01, 0x11, 0x22, 0x33) +
            b(0, 0, 0, 1) + convertedRpu
        assertArrayEquals(expected, rw.output.copyOf(n))
        assertEquals(1L, rw.elDropped)
        assertEquals(1L, rw.rpuConverted)
        assertEquals("32 1 63 62", rw.lastNalTypes)
    }

    @Test
    fun failedConversionKeepsOriginalRpu() {
        val au = slice + rpu
        val rw = DvP8FrameRewriter { _, _, _ -> null }
        val n = rw.rewrite(au, au.size)
        val expected = b(0, 0, 0, 1, 0x02, 0x01, 0x11, 0x22, 0x33) +
            b(0, 0, 0, 1, 0x7C, 0x01, 0x19, 0x08, 0x80)
        assertArrayEquals(expected, rw.output.copyOf(n))
        assertEquals(1L, rw.rpuFailed)
    }

    @Test
    fun frameWithoutStartCodePassesThrough() {
        val au = b(0x12, 0x34, 0x56, 0x78)
        val rw = DvP8FrameRewriter { _, _, _ -> error("not called") }
        val n = rw.rewrite(au, au.size)
        assertArrayEquals(au, rw.output.copyOf(n))
        assertEquals(1L, rw.framesWithoutStartCode)
    }

    @Test
    fun findStartCode() {
        assertEquals(2, DvP8FrameRewriter.findStartCode(b(9, 0, 0, 0, 1, 5), 0, 6))
        assertEquals(-1, DvP8FrameRewriter.findStartCode(b(0, 0, 3, 1, 0, 0), 0, 6))
        assertEquals(3, DvP8FrameRewriter.findStartCode(b(7, 7, 7, 0, 0, 1), 0, 6))
    }
}
