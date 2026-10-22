package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.DeinterlacePolicy
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.from
import io.github.yuroyami.kiteplayer.ofBytes
import io.github.yuroyami.kiteplayer.spi.FieldOrder
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Interlaced video through the FFmpeg backend: the field order reaches the stream info, and the
 * deinterlacer removes the combing. The clip is six 64x48 H.264 frames in Matroska, top field
 * first, made with ffmpeg from vertical stripes that move half a period between the two fields
 * of a frame, so every row of one field disagrees with the rows of the other.
 */
class DeinterlaceTest {

    /** The mean absolute difference between neighbouring rows of the luma plane, out of 255. */
    private fun combing(luma: ByteArray, width: Int, height: Int): Double {
        var total = 0L
        for (row in 0 until height - 1) {
            for (col in 0 until width) {
                total += abs((luma[row * width + col].toInt() and 0xFF) - (luma[(row + 1) * width + col].toInt() and 0xFF))
            }
        }
        return total.toDouble() / ((height - 1) * width)
    }

    /** The combing of the first frame the backend decodes under [policy]. */
    private fun firstFrameCombing(policy: DeinterlacePolicy): Double = runBlocking {
        val item = MediaItem.from(MediaIo.ofBytes(INTERLACED), label = "interlaced.mkv")
        val source = KiteFFmpegSourceFactory().open(item) as KiteFFmpegSource
        try {
            val stream = assertNotNull(source.streams.firstOrNull { it.kind == TrackKind.Video })
            assertEquals(FieldOrder.TopFirst, stream.fieldOrder, "the container says top field first")
            source.selectStreams(setOf(stream.index))
            val decoder = assertNotNull(
                KiteFFmpegVideoDecoderFactory(source).create(stream, HwdecPolicy.Off, policy),
            )
            try {
                var frame: VideoFrame? = null
                while (frame == null) {
                    val packet = source.readPacket()
                    if (packet == null) {
                        decoder.send(null)
                        frame = decoder.receive()
                        break
                    }
                    try {
                        while (!decoder.send(packet)) frame = decoder.receive() ?: break
                    } finally {
                        packet.close()
                    }
                    if (frame == null) frame = decoder.receive()
                }
                val decoded = assertNotNull(frame, "no frame decoded") as KiteFFmpegVideoFrame
                try {
                    val width = decoded.size.width
                    val height = decoded.size.height
                    combing(decoded.readableFrame().copyPlanesToByteArray(), width, height)
                } finally {
                    decoded.close()
                }
            } finally {
                decoder.close()
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun theDeinterlacerRemovesTheCombingThatOffLeaves() {
        // Off first, so the measure is shown to see combing before it is used to show it gone.
        val off = firstFrameCombing(DeinterlacePolicy.Off)
        assertTrue(off > 20.0, "without deinterlacing the rows should disagree, measured $off")
        val auto = firstFrameCombing(DeinterlacePolicy.Auto)
        assertTrue(auto < 8.0, "with deinterlacing the rows should agree, measured $auto against $off")
        val always = firstFrameCombing(DeinterlacePolicy.Always)
        assertTrue(always < 8.0, "Always should deinterlace too, measured $always")
        println("combing of the first frame: off $off, auto $auto, always $always")
    }

    private companion object {
        val INTERLACED: ByteArray = (
            "1a45dfa3a34286810142f7810142f2810442f381084282886d6174726f736b614287810442858102185380670100" +
            "000000000610114d9b74c0bf8498bdf6934dbb8b53ab841549a96653ac81a14dbb8b53ab841654ae6b53ac81cc4d" +
            "bb8c53ab841254c36753ac82016d4dbb8c53ab841c53bb6b53ac8205f4ec01000000000000530000000000000000" +
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000000000000000000000000000001549a966a6bf84952deb9f2ad7b1830f42" +
            "404d80844c6176665741844c617666448988406e0000000000001654ae6b409bbf84d1c96c99ae01000000000000" +
            "8cd7810173c58800000000000000019c810022b59c83756e64888100868f565f4d504547342f49534f2f41564383" +
            "810123e3838402625a00e093b08140ba81309a81019d810955b08455b9810155ee8100ec01000000000000020000" +
            "63a2ae01640015ffe1001967640015acd9444fcb808800000300080000030190f8a14cb001000668fba04b2c8bfd" +
            "f8f8001254c367d7bf84a497d06b7373ce63c08b63c588000000000000000167c89945a387454e434f4445524487" +
            "8c4c617663206c69627832363467c8a145a3884455524154494f4e44879330303a30303a30302e32343030303030" +
            "3030001f43b6754425bf84748b936de78100a3437f81000080000002ab0605ffffa7dc45e9bde6d948b7962cd820" +
            "d923eeef78323634202d20636f7265203136352072333232322062333536303561202d20482e3236342f4d504547" +
            "2d342041564320636f646563202d20436f70796c65667420323030332d32303235202d20687474703a2f2f777777" +
            "2e766964656f6c616e2e6f72672f783236342e68746d6c202d206f7074696f6e733a2063616261633d3120726566" +
            "3d33206465626c6f636b3d313a303a3020616e616c7973653d3078333a3078313133206d653d686578207375626d" +
            "653d37207073793d31207073795f72643d312e30303a302e3030206d697865645f7265663d31206d655f72616e67" +
            "653d3136206368726f6d615f6d653d31207472656c6c69733d31203878386463743d312063716d3d302064656164" +
            "7a6f6e653d32312c313120666173745f70736b69703d31206368726f6d615f71705f6f66667365743d2d32207468" +
            "72656164733d31206c6f6f6b61686561645f746872656164733d3120736c696365645f746872656164733d30206e" +
            "723d3020646563696d6174653d3120696e7465726c616365643d74666620626c757261795f636f6d7061743d3020" +
            "636f6e73747261696e65645f696e7472613d3020626672616d65733d3320625f707972616d69643d3220625f6164" +
            "6170743d3120625f626961733d30206469726563743d3120776569676874623d31206f70656e5f676f703d302077" +
            "6569676874703d30206b6579696e743d36206b6579696e745f6d696e3d31207363656e656375743d343020696e74" +
            "72615f726566726573683d302072635f6c6f6f6b61686561643d362072633d637266206d62747265653d31206372" +
            "663d382e302071636f6d703d302e36302071706d696e3d302071706d61783d3639207170737465703d342069705f" +
            "726174696f3d312e34302061713d313a312e30300080000000050601013280000000bf65888202021ffc6969c0c2" +
            "71f1a96d38a354615339711f4d2e895f12ea8675d2b734c625b74db1465dea98173c1589fa83fa42b0dc2d7f6d4f" +
            "c70a6bab19cb792d0bbd27cc9ae3d2b7a548f738f953f363b8ae0b186a1ce56500404cf28b801c02e3840392d921" +
            "87aa5e880f45d09143b820fcd0cf9a42749b4fe2381731ca4386e7d5832503f88eb058b9dc107fe3a5d65391e1b4" +
            "6e2e05e9749ee33ec17f46b9c7addcffab064b0ca9ced001b46e2ec3440a67c75fb649a4a71e9442c729a39d8100" +
            "a0000000000506010132800000000c419a2216429fd74748bc53a0a39c810050000000000506010132800000000b" +
            "419e4117884bffd27e8fe3a39c810028000000000506010132800000000b019e6097442dffd6fe21d4a39c810078" +
            "000000000506010132800000000b019e6196a42dffd6fe21d5a39f8100c8000000000506010132800000000e419a" +
            "62934b4442dfcc9dd93360c11c53bb6b97bf847f0ad533bb8fb38100b78af78101f18201c9f08109"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
