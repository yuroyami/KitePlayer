package io.github.yuroyami.kiteplayer.libass

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop half of the packaging: the adapter bundled in this module's jar unpacks and loads on
 * the host running the test, with the chain linked in. Every other test in this source set renders
 * through it, so this one only names the failure that would otherwise hide behind theirs.
 */
class LibassJvmAdapterTest {

    @Test
    fun theBundledAdapterLoadsOnThisHost() {
        assertNull(LibassNative.loadFailure, "the bundled kiteplayer_libass_jni did not load")
        assertTrue(LibassNative.libraryVersion() >= 0x01700000, "libass version ${LibassNative.libraryVersion().toString(16)}")
    }

    @Test
    fun theJarCarriesAManifestForThisHost() {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val dir = (if ("mac" in os) "macos" else if ("win" in os) "windows" else "linux") + "-" +
            (if (arch in setOf("aarch64", "arm64")) "arm64" else "x64")
        val manifest = LibassTypesetter::class.java.getResourceAsStream("/$RESOURCE_ROOT/$dir/manifest.txt")
        assertTrue(manifest != null, "no bundled adapter manifest for $dir in the jar resources")
    }
}
