package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The REAL baseline for the conversion cost, not the mirror W-14's benchmark used.
 *
 * It lives here because `tightlyPackedToRgba` is internal to this module, and W.4's 9.4 ms was
 * measured through a whole draw phase under load. This times the function itself, so the next
 * decision rests on the function's own number.
 *
 * Not an assertion, a measurement: it prints and always passes. Run it with
 * `./gradlew :kiteplayer-ffmpeg:jvmTest --tests "*ConversionCostTest*" -i`.
 */
class ConversionCostTest {

    @Test
    fun measureTheRealConverter() {
        val width = 1920
        val height = 1080
        val chromaWidth = (width + 1) shr 1
        val chromaHeight = (height + 1) shr 1
        val planes = ByteArray(width * height + 2 * chromaWidth * chromaHeight) {
            (it * 31 % 255).toByte()
        }
        val space = ColorSpaceInfo()

        fun once(): Int = tightlyPackedToRgba(
            bytes = planes,
            width = width,
            height = height,
            pixelFormat = PlayerPixelFormat.Yuv420p,
            colorSpace = space,
        ).size

        // Not vacuous: a measurement that stopped converting would otherwise time an empty loop.
        assertEquals(width * height * 4, once())

        repeat(20) { once() }
        val samples = DoubleArray(60)
        for (i in samples.indices) {
            val start = System.nanoTime()
            once()
            samples[i] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        println(
            "REAL tightlyPackedToRgba 1920x1080 yuv420p: " +
                "mean=${"%.2f".format(samples.average())} ms " +
                "p50=${"%.2f".format(samples[30])} ms " +
                "p95=${"%.2f".format(samples[57])} ms",
        )
    }

    /**
     * The SPLIT of what W.4 measured as one 9.4 ms number, on a real decoded frame.
     *
     * `SoftwareConverter.toRgba` is two costs stacked: a JNI copy of the packed planes out of
     * native memory, and the conversion loop over them. Optimising the wrong one buys nothing, so
     * this measures each before anything is changed.
     */
    @Test
    fun measureTheSplitOnARealFrame() = runBlocking {
        val dir = System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"
        val file = File(dir, "sync1080p30.mp4")
        if (!file.isFile) return@runBlocking println("SKIP: no ${file.path}")

        val source = KiteCodecSourceFactory().open(MediaItem(file.absolutePath)) as KiteCodecSource
        try {
            val stream = source.firstVideo ?: return@runBlocking println("SKIP: no video stream")
            source.selectStreams(setOf(stream.index))
            val decoder = source.videoDecoderFactories().first()
                .create(stream, io.github.yuroyami.kiteplayer.HwdecPolicy.Off) ?: return@runBlocking
            var decoded: VideoFrame? = null
            while (decoded == null) {
                val packet = source.readPacket() ?: break
                if (packet.streamIndex != stream.index) { packet.close(); continue }
                while (!decoder.send(packet)) {
                    decoded = decoder.receive()
                    if (decoded != null) break
                }
                packet.close()
                if (decoded == null) decoded = decoder.receive()
            }
            val frame = decoded as? KiteCodecVideoFrame ?: return@runBlocking println("SKIP: no frame")
            try {
                fun timed(label: String, block: () -> Unit) {
                    repeat(20) { block() }
                    val samples = DoubleArray(60)
                    for (i in samples.indices) {
                        val start = System.nanoTime()
                        block()
                        samples[i] = (System.nanoTime() - start) / 1_000_000.0
                    }
                    samples.sort()
                    println("$label mean=${"%.2f".format(samples.average())} ms p95=${"%.2f".format(samples[57])} ms")
                }
                assertEquals(frame.size.width * frame.size.height * 4, SoftwareConverter.toRgba(frame).size)
                timed("whole SoftwareConverter.toRgba") { SoftwareConverter.toRgba(frame) }
                timed("JNI copyPlanesToByteArray only") { frame.readableFrame().copyPlanesToByteArray() }
                val planes = frame.readableFrame().copyPlanesToByteArray()
                val info = frame.readableFrame().info
                timed("conversion loop only") {
                    tightlyPackedToRgba(
                        bytes = planes,
                        width = frame.size.width,
                        height = frame.size.height,
                        pixelFormat = info.pixelFormat.toPlayerFormat(),
                        colorSpace = info.color.toPlayerColorSpace(),
                    )
                }
            } finally {
                frame.close()
            }
        } finally {
            source.close()
        }
    }
}
