/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The toolchain's unpack of Kotlin/Native counts only once its `.flag` file exists and still matches it. */
class FindKonancTest {

    private val version = "0.0.0-findkonanc-test"

    @Test
    fun `a flagged unpack is taken over an unflagged one`() = withFakeHome { cache ->
        unpack(cache, "a1", flagged = false)
        val done = unpack(cache, "b2", flagged = true)

        assertEquals(done.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
    }

    @Test
    fun `an unpack still under way is waited for until its flag appears`() = withFakeHome { cache ->
        val running = unpack(cache, "c3", flagged = false)
        val writer = thread {
            Thread.sleep(1_500)
            writeFlag(running, running.listFiles()!!.size)
        }

        assertEquals(running.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
        writer.join()
    }

    @Test
    fun `a flagged unpack with no compiler jar is passed over`() = withFakeHome { cache ->
        unpack(cache, "d4", flagged = true, complete = false)
        val done = unpack(cache, "e5", flagged = true)

        assertEquals(done.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
    }

    @Test
    fun `a flagged folder that is unpacked again is waited for`() = withFakeHome { cache ->
        // The toolchain empties the folder and unpacks again, while the old flag still names three entries.
        val again = unpack(cache, "f6", flagged = true)
        writeFlag(again, again.listFiles()!!.size + 1)
        assertFalse(isUnpackDone(again))
        val writer = thread {
            Thread.sleep(1_500)
            again.resolve("tools").mkdirs()
        }

        assertEquals(again.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
        assertTrue(isUnpackDone(again))
        writer.join()
    }

    @Test
    fun `a copy in the konan data directory comes before the toolchain's`() = withFakeHome { cache ->
        unpack(cache, "g7", flagged = true)
        val konan = konanDataDir().resolve("kotlin-native-prebuilt-host-$version")
        konan.resolve(launcher).apply { parentFile.mkdirs(); writeText("") }
        konan.resolve(COMPILER_JAR).apply { parentFile.mkdirs(); writeText("") }

        assertEquals(konan.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
    }

    private val launcher = if (hostIsWindows) "bin/konanc.bat" else "bin/konanc"

    /**
     * One folder of `extract.cache`, named the way the toolchain names it, with a launcher in it.
     * A [complete] folder also holds the compiler jar, so it has two top-level entries.
     */
    private fun unpack(cache: File, hash: String, flagged: Boolean, complete: Boolean = true): File {
        val dir = cache.resolve("extract.cache/0000000000-kotlin-native-prebuilt-$version-host.tar.gz.$hash.d")
        dir.resolve(launcher).apply { parentFile.mkdirs(); writeText("") }
        if (complete) dir.resolve(COMPILER_JAR).apply { parentFile.mkdirs(); writeText("") }
        if (flagged) writeFlag(dir, dir.listFiles()!!.size)
        return dir
    }

    /** A flag in the toolchain's format, naming [entries] top-level entries. */
    private fun writeFlag(dir: File, entries: Int) {
        File(dir.path + ".flag").writeText("4\n${dir.path}.tar.gz\ntopLevelEntries:$entries\noptions:s")
    }

    /** Runs [block] with `user.home` in a fresh folder, so no real toolchain cache or konan folder is seen. */
    private fun withFakeHome(block: (cache: File) -> Unit) {
        val realHome = System.getProperty("user.home")
        val home = Files.createTempDirectory("find-konanc").toFile()
        System.setProperty("user.home", home.path)
        // XDG_CACHE_HOME, LOCALAPPDATA or KONAN_DATA_DIR can still point at real folders, so clean by name too.
        val cache = toolchainCacheDir()
        try {
            block(cache)
        } finally {
            System.setProperty("user.home", realHome)
            home.deleteRecursively()
            (cache.resolve("extract.cache").listFiles().orEmpty().toList() + konanDataDir().listFiles().orEmpty())
                .filter { version in it.name }
                .forEach { it.deleteRecursively() }
        }
    }

    private companion object {
        const val COMPILER_JAR = "konan/lib/kotlin-native-compiler-embeddable.jar"
    }
}
