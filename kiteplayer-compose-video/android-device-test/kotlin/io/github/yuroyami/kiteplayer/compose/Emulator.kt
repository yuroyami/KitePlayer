package io.github.yuroyami.kiteplayer.compose

import android.os.Build
import java.util.Locale

/**
 * Whether these tests run on the Android emulator rather than a phone. The emulator decodes and
 * draws through its host's emulated GPU, which stalls now and then for every process at once
 * (#301), so a wall-clock bound measured on a phone does not hold there.
 */
internal fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase(Locale.US)
    val model = Build.MODEL.lowercase(Locale.US)
    val hardware = Build.HARDWARE.lowercase(Locale.US)
    val product = Build.PRODUCT.lowercase(Locale.US)
    return fingerprint.startsWith("generic") ||
        "emulator" in fingerprint ||
        "sdk_gphone" in model ||
        "emulator" in model ||
        "ranchu" in hardware ||
        "goldfish" in hardware ||
        "emulator" in product
}
