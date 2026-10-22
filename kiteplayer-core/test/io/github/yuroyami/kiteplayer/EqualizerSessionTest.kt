@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.CoreCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The equaliser reaches the device, and costs nothing when it is off.
 *
 * The filters themselves are measured against real sines in `EqualizerStageTest`. What only a
 * session can show is the wiring: that a setting reaches the feeder's pipeline, that it survives a
 * pipeline rebuilt for a format change, and that flat leaves the audio exactly as it was.
 */
class EqualizerSessionTest {

    private suspend fun CoreHarness.setEqualizer(settings: EqualizerSettings) {
        core.post(CoreCommand.SetEqualizer(settings, CompletableDeferred()))
        run(20.milliseconds)
    }

    private fun boosted(band: Int, db: Float): EqualizerSettings =
        EqualizerSettings(MutableList(10) { 0f }.also { it[band] = db })

    /**
     * The session-level probe is the PREAMP, not a band.
     *
     * The scripted source is a constant, which is a signal at 0 Hz, and a peaking filter at 1 kHz
     * passes 0 Hz at unity: that is what makes it a peaking filter rather than a shelf. So a band
     * boost is invisible here however correct it is, and asserting on one would be asserting that
     * the DSP is wrong. The bands are measured against real sines in EqualizerStageTest; what a
     * session can show is that the stage runs at all, and the preamp scales everything including
     * a constant.
     */
    private fun preamp(db: Float): EqualizerSettings = EqualizerSettings(List(10) { 0f }, preampDb = db)

    @Test
    fun `flat leaves the audio exactly as it was`() = runTest {
        // The default, and the one that must stay free: the scripted source is a constant at full
        // scale, so any filtering at all would move it off 1.0.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)
        assertTrue(
            harness.sink.audibleValues.all { it == 0f || it == 1f },
            "a flat player altered the samples: ${harness.sink.audibleValues.sorted()}",
        )
        harness.close()
    }

    @Test
    fun `a boost reaches the device`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)

        harness.setEqualizer(preamp(-6f))
        // Past the ring's depth: the equaliser is applied on the way IN, so what is already
        // buffered was filtered under the old settings and has to drain first.
        harness.run(600.milliseconds)
        harness.sink.clearChannelPeaks()
        harness.run(300.milliseconds)

        val peak = harness.sink.channelPeak(0)
        assertTrue(
            peak in 0.4f..0.6f,
            "a -6 dB preamp should have halved a full-scale constant, heard $peak",
        )
        harness.close()
    }

    @Test
    fun `the snapshot carries what was set`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        assertEquals(EqualizerSettings.Flat, harness.core.snapshots.value.equalizer)
        val settings = boosted(band = 2, db = -6f)
        harness.setEqualizer(settings)
        assertEquals(settings, harness.core.snapshots.value.equalizer)
        harness.close()
    }

    @Test
    fun `a player configured with an equaliser opens with it`() = runTest {
        val settings = preamp(-6f)
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 4_000_000),
            config = PlayerConfig(audio = AudioConfig(equalizer = settings)),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(400.milliseconds)
        assertEquals(settings, harness.core.snapshots.value.equalizer)
        assertTrue(
            harness.sink.channelPeak(0) in 0.4f..0.6f,
            "the configured equaliser was not applied at open, heard ${harness.sink.channelPeak(0)}",
        )
        harness.close()
    }

    @Test
    fun `going back to flat restores the untouched samples`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 6_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(200.milliseconds)
        harness.setEqualizer(preamp(-6f))
        harness.run(600.milliseconds)

        harness.setEqualizer(EqualizerSettings.Flat)
        harness.run(800.milliseconds)
        harness.sink.clearChannelPeaks()
        harness.run(300.milliseconds)

        assertEquals(
            1f,
            harness.sink.channelPeak(0),
            absoluteTolerance = 1e-6f,
            message = "returning to flat left the audio filtered",
        )
        harness.close()
    }
}
