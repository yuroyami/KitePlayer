package io.github.yuroyami.kiteplayer.buildtools

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NdkVersionOrderTest {

    @Test
    fun `a two-digit minor is newer than a one-digit minor`() {
        assertTrue(NdkVersionOrder.compare("29.10.0", "29.2.0") > 0)
        assertEquals("29.10.0", listOf("29.2.0", "29.10.0", "28.2.13676358").maxWithOrNull(NdkVersionOrder))
    }

    @Test
    fun `build numbers compare as numbers`() {
        assertTrue(NdkVersionOrder.compare("27.0.12077973", "27.0.9999999") > 0)
        assertEquals(0, NdkVersionOrder.compare("29.0.14206865", "29.0.14206865"))
    }

    @Test
    fun `a suffixed build sorts below the release of the same number`() {
        assertTrue(NdkVersionOrder.compare("29.0.14206865-rc1", "29.0.14206865") < 0)
        assertTrue(NdkVersionOrder.compare("29.0", "29.0.1") < 0)
    }

    @Test
    fun `the newest side-by-side NDK folder wins and files are ignored`() {
        val root = createTempDirectory("ndk").toFile()
        try {
            listOf("27.0.12077973", "29.2.0", "29.10.0").forEach { root.resolve(it).mkdirs() }
            root.resolve("29.99.0 alias").writeText("a Finder alias is a file, not an NDK")
            assertEquals("29.10.0", newestNdk(root)?.name)
            assertNull(newestNdk(File(root, "missing")))
        } finally {
            root.deleteRecursively()
        }
    }
}
