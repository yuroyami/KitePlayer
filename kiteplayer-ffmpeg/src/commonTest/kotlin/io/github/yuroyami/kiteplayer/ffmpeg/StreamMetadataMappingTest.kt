package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kitecodec.ChromaLocation
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.ColorInfo
import io.github.yuroyami.kitecodec.ColorMatrix
import io.github.yuroyami.kitecodec.ColorPrimaries
import io.github.yuroyami.kitecodec.ColorTransfer
import io.github.yuroyami.kitecodec.MediaType
import io.github.yuroyami.kitecodec.PixelFormat
import io.github.yuroyami.kitecodec.Rational
import io.github.yuroyami.kitecodec.StreamInfo
import io.github.yuroyami.kitecodec.VideoStreamInfo
import io.github.yuroyami.kitecodec.Vp9BitDepth
import io.github.yuroyami.kitecodec.Vp9ChromaSubsampling
import io.github.yuroyami.kitecodec.Vp9CodecInfo
import io.github.yuroyami.kitecodec.Vp9Level
import io.github.yuroyami.kitecodec.Vp9Profile
import io.github.yuroyami.kiteplayer.spi.ColorMatrix as PlayerColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorPrimaries as PlayerColorPrimaries
import io.github.yuroyami.kiteplayer.spi.ColorTransfer as PlayerColorTransfer
import io.github.yuroyami.kiteplayer.spi.Vp9BitDepth as PlayerVp9BitDepth
import io.github.yuroyami.kiteplayer.spi.Vp9ChromaSubsampling as PlayerVp9ChromaSubsampling
import io.github.yuroyami.kiteplayer.spi.Vp9Level as PlayerVp9Level
import io.github.yuroyami.kiteplayer.spi.Vp9Profile as PlayerVp9Profile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class StreamMetadataMappingTest {

    @Test
    fun fullRangeJpegPixelFormatsMapToTheirYuvTwins() {
        assertEquals(io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Yuv420p, PixelFormat("yuvj420p").toPlayerFormat())
        assertEquals(io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Yuv422p, PixelFormat("yuvj422p").toPlayerFormat())
        assertEquals(io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat.Yuv444p, PixelFormat("yuvj444p").toPlayerFormat())
    }
    @Test
    fun streamColorVp9AndExtradataCrossTheBackendBoundaryLosslessly() {
        val extradata = byteArrayOf(1, 2, 3)
        val source = StreamInfo(
            index = 4,
            type = MediaType.Video,
            codec = CodecId.Vp9,
            timeBase = Rational(1, 1_000),
            durationMicros = 5_000_000,
            bitrateBps = 1_000_000,
            video = VideoStreamInfo(
                width = 1_920,
                height = 1_080,
                pixelFormat = PixelFormat.Yuv420p,
                frameRate = Rational(24, 1),
                sampleAspectRatio = Rational(1, 1),
                color = ColorInfo(
                    matrix = ColorMatrix.Bt2020Ncl,
                    primaries = ColorPrimaries.Bt2020,
                    transfer = ColorTransfer.Bt2020Ten,
                    fullRange = false,
                    chromaLocation = ChromaLocation.TopLeft,
                    rangeSpecified = false,
                ),
                vp9 = Vp9CodecInfo(
                    profile = Vp9Profile.Profile0,
                    level = Vp9Level.Level4_1,
                    bitDepth = Vp9BitDepth.Eight,
                    chromaSubsampling = Vp9ChromaSubsampling.Yuv420,
                ),
            ),
            codecExtradata = extradata,
        )

        val mapped = requireNotNull(source.toPlayerStream(TimestampMapper(0)))
        val color = requireNotNull(mapped.colorSpace)
        assertEquals(PlayerColorMatrix.Bt2020Ncl, color.matrix)
        assertEquals(PlayerColorPrimaries.Bt2020, color.primaries)
        assertEquals(PlayerColorTransfer.Bt2020Ten, color.transfer)
        assertFalse(color.rangeSpecified)
        assertEquals(PlayerVp9Profile.Profile0, mapped.vp9?.profile)
        assertEquals(PlayerVp9Level.Level4_1, mapped.vp9?.level)
        assertEquals(PlayerVp9BitDepth.Eight, mapped.vp9?.bitDepth)
        assertEquals(PlayerVp9ChromaSubsampling.Yuv420, mapped.vp9?.chromaSubsampling)
        assertContentEquals(extradata, mapped.codecExtradata)
        assertNotSame(extradata, mapped.codecExtradata)
    }
}
