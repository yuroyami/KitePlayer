package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a CoreAudio output is taken for, and which changes of route make private sound loud (#503).
 * The numbers are CoreAudio's four-character codes, written out as a device reports them.
 */
class OutputRouteTest {

    private fun code(text: String): UInt = text.fold(0u) { value, character -> (value shl 8) or character.code.toUInt() }

    private val builtIn = code("bltn")
    private val bluetooth = code("blue")
    private val bluetoothLe = code("blea")
    private val usb = code("usb ")
    private val hdmi = code("hdmi")

    @Test
    fun theDevicesOwnWordComesBeforeHowItIsConnected() {
        assertEquals(OutputRouteKind.Headphones, classifyOutput(builtIn, code("hdph"), null), "the headphone jack of a Mac")
        assertEquals(OutputRouteKind.Headphones, classifyOutput(builtIn, null, code("hdpn")), "a built-in device switched to its jack")
        assertEquals(OutputRouteKind.Speakers, classifyOutput(builtIn, code("spkr"), null))
        assertEquals(OutputRouteKind.Speakers, classifyOutput(builtIn, null, code("ispk")))
        assertEquals(OutputRouteKind.Headphones, classifyOutput(bluetooth, code("hdph"), null))
        assertEquals(OutputRouteKind.Speakers, classifyOutput(bluetooth, code("spkr"), null), "a Bluetooth speaker is a speaker")
        assertEquals(OutputRouteKind.Headphones, classifyOutput(usb, code("hdph"), null), "a USB headset")
    }

    @Test
    fun aBluetoothDeviceThatSaysNothingIsTakenForHeadphonesAndTheRestIsUnknown() {
        assertEquals(OutputRouteKind.UnnamedBluetooth, classifyOutput(bluetooth, null, null))
        assertEquals(OutputRouteKind.UnnamedBluetooth, classifyOutput(bluetoothLe, code("????"), null))
        assertEquals(OutputRouteKind.Unknown, classifyOutput(hdmi, null, null))
        assertEquals(OutputRouteKind.Unknown, classifyOutput(usb, null, null))
        assertEquals(OutputRouteKind.Unknown, classifyOutput(builtIn, null, null))
        assertEquals(OutputRouteKind.Unknown, classifyOutput(null, null, null))
    }

    @Test
    fun onlyHeadphonesGivingWayToSpeakersIsNoisy() {
        assertTrue(becameNoisy(OutputRouteKind.Headphones, OutputRouteKind.Speakers))
        assertTrue(becameNoisy(OutputRouteKind.UnnamedBluetooth, OutputRouteKind.Speakers))
        for (from in OutputRouteKind.entries) {
            for (to in OutputRouteKind.entries) {
                val expected = (from == OutputRouteKind.Headphones || from == OutputRouteKind.UnnamedBluetooth) &&
                    to == OutputRouteKind.Speakers
                assertEquals(expected, becameNoisy(from, to), "$from to $to")
            }
        }
        assertFalse(becameNoisy(OutputRouteKind.Speakers, OutputRouteKind.Speakers), "one speaker to another keeps playing")
        assertFalse(becameNoisy(OutputRouteKind.Speakers, OutputRouteKind.Headphones), "plugging in is never noisy")
        assertFalse(becameNoisy(OutputRouteKind.Headphones, OutputRouteKind.Unknown), "headphones to a television is not known to be loud")
    }
}
