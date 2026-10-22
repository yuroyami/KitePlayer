package io.github.yuroyami.kiteplayer.output

import android.media.AudioDeviceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The channels Android's media route carries, as the channel match reads them (#466). */
class AudioRouteChannelsTest {

    @Test
    fun headphonesBluetoothAndTheSpeakerCarryTwoWhateverTheyList() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )) {
            assertEquals(2, routeChannelCount(type, intArrayOf(1, 2, 6, 8)), "type $type")
            assertEquals(2, routeChannelCount(type, intArrayOf()), "type $type")
        }
    }

    @Test
    fun hdmiCarriesTheMostItListsAndNothingWhenItListsNone() {
        assertEquals(6, routeChannelCount(AudioDeviceInfo.TYPE_HDMI, intArrayOf(2, 6)))
        assertEquals(8, routeChannelCount(AudioDeviceInfo.TYPE_HDMI_EARC, intArrayOf(8, 2)))
        assertNull(routeChannelCount(AudioDeviceInfo.TYPE_HDMI, intArrayOf()))
    }

    @Test
    fun headsetsWinOverHdmiAndHdmiOverTheSpeaker() {
        assertTrue(routePriority(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) < routePriority(AudioDeviceInfo.TYPE_HDMI))
        assertTrue(routePriority(AudioDeviceInfo.TYPE_WIRED_HEADSET) < routePriority(AudioDeviceInfo.TYPE_HDMI))
        assertTrue(routePriority(AudioDeviceInfo.TYPE_HDMI) < routePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertTrue(routePriority(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) < routePriority(AudioDeviceInfo.TYPE_TELEPHONY))
    }
}
