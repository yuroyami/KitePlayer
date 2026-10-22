package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIoFactory
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A stream that seeks by opening it again, as a compressed bundled resource reads (#457), held to every reader's contract. */
class ReopeningStreamMediaIoTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory =
        MediaIoFactory { ReopeningStreamMediaIo({ ByteArrayInputStream(bytes) }, bytes.size.toLong()) }

    @Test
    fun anAndroidAssetAddressNamesItsAsset() {
        assertEquals(
            "composeResources/app.generated.resources/files/intro clip.mp4",
            assetNameOf("file:///android_asset/composeResources/app.generated.resources/files/intro%20clip.mp4"),
        )
        assertEquals("files/a+b é.mp4", assetNameOf("file:///android_asset/files/a+b%20%C3%A9.mp4"))
        assertNull(assetNameOf("file:///sdcard/Movies/intro.mp4"))
        assertNull(assetNameOf("file:///android_asset/"))
    }
}
