package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame

class KitePlayerPlatformAndroidTest {
    @Test
    fun packagedJniPathIsAvailableWithoutLoadingTheLibrary() {
        assertSame(
            KitePlayerAvailability.Available,
            androidKitePlayerAvailability("/apk/lib/arm64-v8a/libkitecodec_jni.so"),
        )
    }

    @Test
    fun absentOrBlankJniPathIsUnavailable() {
        listOf(null, "", "  ").forEach { path ->
            val availability = assertIs<KitePlayerAvailability.Unavailable>(
                androidKitePlayerAvailability(path),
            )
            assertFalse(availability.isAvailable)
        }
    }
}
