package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.audioviz.viz.AnticipatedImpulse
import io.github.yuroyami.kiteplayer.audioviz.viz.motion.Spring
import kotlin.test.Test
import kotlin.test.assertEquals

class AnticipatedImpulseTest {
    private fun event(sequence: Long, strength: Float = 0.3f, kind: AudioEventKind = AudioEventKind.LowTransient,
        generation: Generation = Generation.Initial) = AudioEvent(generation, 0L, sequence,
        AudioDetection(kind, sequence * 50_000L, sequence * 50_000L + 10_000L, strength, 0.8f, 0.2f))

    private fun frame(vararg events: AudioEvent, generation: Generation = Generation.Initial,
        reset: Boolean = false): SpectrumFrame = SpectrumFrame(
        100_000L, FloatArray(4), FloatArray(4), FloatArray(4), 0f, 0f, 0f, 0f, 0f, 0f,
        generation = generation,
        events = AudioEventDelivery(generation, 0L, 100_000L,
            Array(events.size) { DeliveredAudioEvent(events[it], 10_000L) }, reset = reset),
    )

    private fun assertSpring(expected: Spring, actual: Spring) {
        assertEquals(expected.speed, actual.speed, 1e-6f)
        repeat(30) {
            expected.advance(0.01f)
            actual.advance(0.01f)
            assertEquals(expected.value, actual.value, 1e-6f)
        }
    }

    @Test
    fun closeFutureHitsRearmAndLaterDeliveryDoesNotRepeatEitherHit() {
        val spring = Spring(90f, 0.5f)
        val impulse = AnticipatedImpulse(spring)
        val first = event(1)
        val second = event(2, 0.4f)
        impulse.apply(frame(), UpcomingAudioEvent(first, 0.05f), 5f)
        impulse.apply(frame(), UpcomingAudioEvent(first, 0.04f), 5f)
        assertEquals(1.5f, spring.speed, 1e-6f, "the same future event must not repeat")
        impulse.apply(frame(), UpcomingAudioEvent(second, 0.05f), 5f)
        assertEquals(3.5f, spring.speed, 1e-6f, "the second event must act before delivery")
        impulse.apply(frame(first, second), null, 5f)
        val expected = Spring(90f, 0.5f)
        expected.kick(0.3f * 5f)
        expected.kick(0.4f * 5f)
        assertSpring(expected, spring)
    }

    @Test
    fun anticipatingOneIdentityDoesNotSuppressADifferentLateHit() {
        val spring = Spring(90f, 0.5f)
        val impulse = AnticipatedImpulse(spring)
        val anticipated = event(2)
        val late = event(1, 0.4f)
        impulse.apply(frame(), UpcomingAudioEvent(anticipated, 0.05f), 5f)
        impulse.apply(frame(late), null, 5f)
        impulse.apply(frame(anticipated), null, 5f)
        val expected = Spring(90f, 0.5f)
        expected.kick(0.3f * 5f)
        expected.kick(0.4f * 5f)
        assertSpring(expected, spring)
    }

    @Test
    fun allDeliveredLowHitsContributeAndOtherKindsDoNot() {
        val spring = Spring(90f, 0.5f)
        AnticipatedImpulse(spring).apply(frame(event(1), event(2, 0.4f),
            event(3, 1f, AudioEventKind.BodyTransient)), null, 5f)
        val expected = Spring(90f, 0.5f)
        expected.kick(0.3f * 5f)
        expected.kick(0.4f * 5f)
        assertSpring(expected, spring)
    }

    @Test
    fun aResetOrChangedIdentityRetiresAnticipation() {
        for (changedGeneration in listOf(false, true)) {
            val spring = Spring(90f, 0.5f)
            val impulse = AnticipatedImpulse(spring)
            impulse.apply(frame(), UpcomingAudioEvent(event(1), 0.05f), 5f)
            val generation = if (changedGeneration) Generation(1) else Generation.Initial
            val replacement = event(1, generation = generation)
            impulse.apply(frame(generation = generation, reset = !changedGeneration),
                UpcomingAudioEvent(replacement, 0.05f), 5f)
            impulse.apply(frame(replacement, generation = generation), null, 5f)
            val expected = Spring(90f, 0.5f)
            repeat(2) { expected.kick(0.3f * 5f) }
            assertSpring(expected, spring)
        }
    }
}
