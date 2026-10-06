package io.github.yuroyami.kiteplayer.output

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * How many channels the route that media plays through carries, or null when Android does not say
 * (#466).
 *
 * From Android 13 the system names the route media takes. Before it, the route is the first output
 * of the kind Android prefers for media: Bluetooth and wired headsets over the HDMI and USB outputs,
 * and those over the phone's own speaker, which is how Android routes media when several are there.
 */
internal fun mediaRouteChannelCount(audioManager: AudioManager): Int? {
    val route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        audioManager.getAudioDevicesForAttributes(media).firstOrNull()
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .minByOrNull { routePriority(it.type) }
    } ?: return null
    return routeChannelCount(route.type, route.channelCounts)
}

/** Lower plays first: what Android routes media to when outputs of several kinds are connected. */
internal fun routePriority(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> 1
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE,
    -> 2
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
    else -> 4
}

/**
 * The channels an output of [type] carries. Headphones, Bluetooth and a phone's own speaker carry
 * two, whatever their profile lists. Anything else carries the most its profile lists, and nothing
 * when its profile lists none, which Android's documentation reads as "any count", because that
 * says nothing about the speakers behind it.
 */
internal fun routeChannelCount(type: Int, channelCounts: IntArray): Int? = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    -> 2
    else -> channelCounts.maxOrNull()?.takeIf { it > 0 }
}
