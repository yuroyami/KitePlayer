/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/** The toolchain's unpack of Kotlin/Native counts only once its `.flag` file exists. */
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
            File(running.path + ".flag").writeText("done")
        }

        assertEquals(running.resolve(launcher).canonicalFile, findKonanc(version)?.canonicalFile)
        writer.join()
    }

    private val launcher = if (hostIsWindows) "bin/konanc.bat" else "bin/konanc"

    /** One folder of `extract.cache`, named the way the toolchain names it, with a launcher in it. */
    private fun unpack(cache: File, hash: String, flagged: Boolean): File {
        val dir = cache.resolve("extract.cache/0000000000-kotlin-native-prebuilt-$version-host.tar.gz.$hash.d")
        dir.resolve(launcher).apply { parentFile.mkdirs(); writeText("") }
        if (flagged) File(dir.path + ".flag").writeText("done")
        return dir
    }

    /** Runs [block] with `user.home` in a fresh folder, so no real toolchain cache is seen. */
    private fun withFakeHome(block: (cache: File) -> Unit) {
        val realHome = System.getProperty("user.home")
        val home = Files.createTempDirectory("find-konanc").toFile()
        System.setProperty("user.home", home.path)
        // XDG_CACHE_HOME or LOCALAPPDATA can still point at the real cache, so clean by name too.
        val cache = toolchainCacheDir()
        try {
            block(cache)
        } finally {
            System.setProperty("user.home", realHome)
            home.deleteRecursively()
            cache.resolve("extract.cache").listFiles().orEmpty()
                .filter { version in it.name }
                .forEach { it.deleteRecursively() }
        }
    }
}
