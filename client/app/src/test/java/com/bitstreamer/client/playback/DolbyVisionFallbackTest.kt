package com.bitstreamer.client.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class DolbyVisionFallbackTest {

    @Test
    fun testShouldFallbackToHdr10_DefaultNativeDVNoFallback() {
        // SDR / HDR10 / HDR10+ / HLG -> No Fallback
        assertFalse(PlayerFactory.shouldFallbackToHdr10(-1, stripDV = false, forceStripDV = false))

        // DV Profile 5 -> No Fallback (Native DV)
        assertFalse(PlayerFactory.shouldFallbackToHdr10(5, stripDV = false, forceStripDV = false))

        // DV Profile 8 -> No Fallback (Native DV)
        assertFalse(PlayerFactory.shouldFallbackToHdr10(8, stripDV = false, forceStripDV = false))

        // DV Profile 7 -> No Fallback (Native DV by default)
        assertFalse(PlayerFactory.shouldFallbackToHdr10(7, stripDV = false, forceStripDV = false))
    }

    @Test
    fun testShouldFallbackToHdr10_ExplicitStripDVTriggersFallbackForAll() {
        // Explicit stripDV flag forces fallback across all profiles (including Profile 7)
        assertTrue(PlayerFactory.shouldFallbackToHdr10(7, stripDV = true, forceStripDV = false))
        assertTrue(PlayerFactory.shouldFallbackToHdr10(5, stripDV = true, forceStripDV = false))
        assertTrue(PlayerFactory.shouldFallbackToHdr10(8, stripDV = true, forceStripDV = false))

        // Explicit forceStripDV flag
        assertTrue(PlayerFactory.shouldFallbackToHdr10(7, stripDV = false, forceStripDV = true))
        assertTrue(PlayerFactory.shouldFallbackToHdr10(5, stripDV = false, forceStripDV = true))
    }

    // ---- planDv (automatic device-agnostic policy) ----
    //
    // The decision must depend ONLY on (dvProfile, hasDvDecoder, userOverride).
    // These tests pin every profile x capability combination and assert that the
    // diagnostic-only flags (nativeDualLayer, displaySupportsDv, dvDecoderName) and
    // hdr10Plus never change the outcome. If a future change reintroduces a
    // device/decoder-name heuristic, these break.

    /** A DV-capable device (decoder present). Vary the diagnostic-only flags freely. */
    private fun dvDevice(
        dual: Boolean = false,
        displayDv: Boolean = true,
        name: String = "OMX.SOME.DV.DECODER",
    ) = DvCaps(hasDvDecoder = true, dvDecoderName = name, nativeDualLayer = dual, displaySupportsDv = displayDv)

    /** A device with no Dolby Vision decoder at all. */
    private val noDvDevice = DvCaps(hasDvDecoder = false, dvDecoderName = null, nativeDualLayer = false, displaySupportsDv = false)

    private fun plan(profile: Int, caps: DvCaps, hdr10Plus: Boolean = false, override: DvPlan? = null) =
        PlayerFactory.planDv(profile, hdr10Plus, caps, override)

    @Test
    fun testPlanDv_Profile7_convertsWheneverDvDecoderPresent() {
        assertEquals(DvPlan.CONVERT_P8, plan(7, dvDevice()))
        // hdr10Plus present (the FraMeSToR hybrid case) must not change the plan.
        assertEquals(DvPlan.CONVERT_P8, plan(7, dvDevice(), hdr10Plus = true))
    }

    @Test
    fun testPlanDv_Profile7_mediaTekDecoderStillConverts_regression() {
        // REGRESSION GUARD (Fire TV Stick 4K/Max): the DV decoder is
        // OMX.MTK.VIDEO.DECODER.DVHE.STN and nativeDualLayer=true, but it is
        // single-layer only. It must CONVERT_P8, never NATIVE (which black-screens FEL).
        val fireTvMax = dvDevice(dual = true, name = "OMX.MTK.VIDEO.DECODER.DVHE.STN")
        assertEquals(DvPlan.CONVERT_P8, plan(7, fireTvMax, hdr10Plus = true))
        // Same for a hypothetical Realtek name — decoder name must be irrelevant.
        assertEquals(DvPlan.CONVERT_P8, plan(7, dvDevice(dual = true, name = "OMX.realtek.video.decoder")))
    }

    @Test
    fun testPlanDv_Profile7_stripsWhenNoDvDecoder() {
        assertEquals(DvPlan.STRIP_HDR10, plan(7, noDvDevice))
    }

    @Test
    fun testPlanDv_Profile8_nativeWithDecoder_stripWithout() {
        assertEquals(DvPlan.NATIVE, plan(8, dvDevice()))
        assertEquals(DvPlan.STRIP_HDR10, plan(8, noDvDevice))
    }

    @Test
    fun testPlanDv_Profile5_alwaysNative_neverStripped() {
        // P5 base is IPTPQc2 (not HDR10): stripping would corrupt colors, so it is
        // NATIVE regardless of decoder presence.
        assertEquals(DvPlan.NATIVE, plan(5, dvDevice()))
        assertEquals(DvPlan.NATIVE, plan(5, noDvDevice))
    }

    @Test
    fun testPlanDv_nonDvProfiles_areNative() {
        // -1 = not DV (SDR/HDR10/HDR10+/HLG), and any unknown/future profile number.
        for (p in intArrayOf(-1, 0, 4, 9, 100)) {
            assertEquals("profile $p with decoder", DvPlan.NATIVE, plan(p, dvDevice()))
            assertEquals("profile $p without decoder", DvPlan.NATIVE, plan(p, noDvDevice))
        }
    }

    @Test
    fun testPlanDv_diagnosticFlagsDoNotAffectDecision() {
        // For a fixed (profile, hasDvDecoder), nativeDualLayer and displaySupportsDv
        // must not change the result. Exhaustively vary them for Profile 7.
        for (dual in booleanArrayOf(true, false)) {
            for (displayDv in booleanArrayOf(true, false)) {
                assertEquals(
                    "P7 decoder-present dual=$dual displayDv=$displayDv",
                    DvPlan.CONVERT_P8,
                    plan(7, dvDevice(dual = dual, displayDv = displayDv)),
                )
            }
        }
    }

    @Test
    fun testPlanDv_userOverrideAlwaysWins() {
        // Override beats the automatic decision for every profile and both devices.
        for (profile in intArrayOf(-1, 5, 7, 8)) {
            for (caps in listOf(dvDevice(dual = true), noDvDevice)) {
                for (override in DvPlan.values()) {
                    assertEquals(
                        "profile=$profile override=$override",
                        override,
                        plan(profile, caps, override = override),
                    )
                }
            }
        }
    }

    @Test
    fun testMapFormat_FallbackActive() {
        val dvFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.07.06")
            .build()

        val mapped = PlayerFactory.mapFormat(dvFormat, fallbackToHdr10 = true)
        assertEquals(MimeTypes.VIDEO_H265, mapped.sampleMimeType)
        assertEquals(null, mapped.codecs)
    }

    @Test
    fun testMapFormat_FallbackInactive() {
        val dvFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
            .setCodecs("dvh1.07.06")
            .build()

        val mapped = PlayerFactory.mapFormat(dvFormat, fallbackToHdr10 = false)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, mapped.sampleMimeType)
        assertEquals("dvh1.07.06", mapped.codecs)
    }

    @Test
    fun testMapFormat_NonDolbyVisionFormatUntouched() {
        val hevcFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.1.6.L153.B0")
            .build()

        val mappedTrue = PlayerFactory.mapFormat(hevcFormat, fallbackToHdr10 = true)
        assertEquals(MimeTypes.VIDEO_H265, mappedTrue.sampleMimeType)

        val mappedFalse = PlayerFactory.mapFormat(hevcFormat, fallbackToHdr10 = false)
        assertEquals(MimeTypes.VIDEO_H265, mappedFalse.sampleMimeType)
    }

    @Test
    fun testStripDolbyVisionNalUnits_RemovesNal62And63() {
        // Construct Annex B stream:
        // NAL 32 (VPS): 00 00 00 01 (32 << 1 = 0x40) FF
        // NAL 62 (RPU): 00 00 00 01 (62 << 1 = 0x7C) AA BB
        // NAL 63 (EL):  00 00 00 01 (63 << 1 = 0x7E) CC DD
        // NAL 1  (SLICE): 00 00 01 (1 << 1 = 0x02) 11 22
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x40.toByte(), 0xFF.toByte(),
            0, 0, 0, 1, 0x7C.toByte(), 0xAA.toByte(), 0xBB.toByte(),
            0, 0, 0, 1, 0x7E.toByte(), 0xCC.toByte(), 0xDD.toByte(),
            0, 0, 1, 0x02.toByte(), 0x11.toByte(), 0x22.toByte()
        )

        val buf = ByteBuffer.allocate(stream.size)
        buf.put(stream)
        buf.flip()

        PlayerFactory.stripDolbyVisionNalUnits(buf)

        // Expected output after stripping NAL 62 and 63:
        // NAL 32 (VPS - 4 byte start code) + NAL 1 (SLICE - 3 byte start code)
        val expected = byteArrayOf(
            0, 0, 0, 1, 0x40.toByte(), 0xFF.toByte(),
            0, 0, 1, 0x02.toByte(), 0x11.toByte(), 0x22.toByte()
        )

        val actual = ByteArray(buf.remaining())
        buf.get(actual)

        assertEquals(expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("Mismatch at byte $i", expected[i], actual[i])
        }
    }

    @Test
    fun testStripDolbyVisionNalUnits_PreservesCleanStream() {
        // Clean HEVC stream without DV NALs
        val stream = byteArrayOf(
            0, 0, 0, 1, 0x40.toByte(), 0xFF.toByte(),
            0, 0, 1, 0x02.toByte(), 0x11.toByte(), 0x22.toByte()
        )

        val buf = ByteBuffer.allocate(stream.size)
        buf.put(stream)
        buf.flip()

        PlayerFactory.stripDolbyVisionNalUnits(buf)

        val actual = ByteArray(buf.remaining())
        buf.get(actual)

        assertEquals(stream.size, actual.size)
        for (i in stream.indices) {
            assertEquals("Mismatch at byte $i", stream[i], actual[i])
        }
    }
}
