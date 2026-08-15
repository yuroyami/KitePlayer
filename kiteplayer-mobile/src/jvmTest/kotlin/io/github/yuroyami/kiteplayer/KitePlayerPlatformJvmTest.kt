package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.mobile.mobileBackends
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitePlayerPlatformJvmTest {
    @Test
    fun desktopPublicationIsAnExplicitNonThrowingPlaceholder() {
        val unavailable = assertIs<KitePlayerAvailability.Unavailable>(
            KitePlayerPlatform.availability,
        )

        assertFalse(KitePlayerPlatform.isAvailable)
        assertFalse(KitePlayerPlatform.supportsPictureInPicture)
        assertTrue(unavailable.reason.contains("Desktop JVM"))
        assertNull(KitePlayerPlatform.createOrNull())
        assertEquals(Backends(), mobileBackends())
    }
}
