package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The widest line the mixer opens is the desktop's answer to how many channels it carries (#466). */
class DesktopOutputChannelsTest {

    private fun mixerTaking(vararg counts: Int) = object : SourceDataLineDriverFactory {
        override fun create(accepted: AudioFormat): SourceDataLineDriver = error("not opened here")
        override fun supports(format: AudioFormat): Boolean = format.channels in counts
    }

    @Test
    fun theWidestLineTheMixerOpensIsTheAnswer() {
        assertEquals(2, widestOutput(mixerTaking(1, 2)))
        assertEquals(6, widestOutput(mixerTaking(2, 6)))
        assertEquals(8, widestOutput(mixerTaking(2, 6, 8)))
        assertNull(widestOutput(mixerTaking(1)))
    }

    @Test
    fun aMixerThatThrowsOpensNothing() {
        val broken = object : SourceDataLineDriverFactory {
            override fun create(accepted: AudioFormat): SourceDataLineDriver = error("not opened here")
            override fun supports(format: AudioFormat): Boolean = throw IllegalStateException("no mixer")
        }
        assertNull(widestOutput(broken))
    }
}
