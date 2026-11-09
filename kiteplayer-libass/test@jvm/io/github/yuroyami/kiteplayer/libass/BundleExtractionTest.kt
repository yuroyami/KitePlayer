package io.github.yuroyami.kiteplayer.libass

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The bundled JNI library is written once per build and reused, so a launch adds no folder to the
 * temporary directory (#651). Windows cannot delete a loaded library, so the old loader's folder
 * per launch stayed behind every time.
 */
class BundleExtractionTest {

    private val temp: File = Files.createTempDirectory("bundle-extraction").toFile()

    @AfterTest
    fun clean() {
        temp.deleteRecursively()
    }

    private val bundle = mapOf("libkiteplayer_libass_jni.so" to ByteArray(4096) { it.toByte() })

    @Test
    fun aSecondLaunchReusesTheFirstOnesCopyAndWritesNothing() {
        val first = extractBundle(temp, bundle)
        val library = File(first, "libkiteplayer_libass_jni.so")
        assertContentEquals(bundle.values.single(), library.readBytes())
        val written = library.lastModified()
        library.setLastModified(written - 10_000)

        val second = extractBundle(temp, bundle)
        assertEquals(first, second)
        assertEquals(written - 10_000, library.lastModified(), "the copy was written again")
        assertEquals(1, temp.listFiles()!!.size, "a launch left another folder: ${temp.list()!!.toList()}")
        assertEquals(listOf("libkiteplayer_libass_jni.so"), first.list()!!.toList())
    }

    @Test
    fun anotherBuildGetsItsOwnFolderAndADamagedCopyIsWrittenAgain() {
        val first = extractBundle(temp, bundle)
        val other = extractBundle(temp, mapOf("libkiteplayer_libass_jni.so" to ByteArray(10) { 7 }))
        assertNotEquals(first, other)

        File(first, "libkiteplayer_libass_jni.so").writeBytes(ByteArray(3))
        extractBundle(temp, bundle)
        assertContentEquals(bundle.values.single(), File(first, "libkiteplayer_libass_jni.so").readBytes())
    }

    @Test
    fun theOldLoadersFoldersGoOnceTheyAreAnHourOld() {
        val now = System.currentTimeMillis()
        val stale = File(temp, "${EXTRACT_PREFIX}123456").apply { mkdirs() }
        File(stale, "kiteplayer_libass_jni.dll").writeBytes(ByteArray(8))
        stale.setLastModified(now - 2 * 60 * 60 * 1000L)
        val starting = File(temp, "${EXTRACT_PREFIX}654321").apply { mkdirs() }
        val unrelated = File(temp, "somebody-else").apply { mkdirs() }
        unrelated.setLastModified(now - 2 * 60 * 60 * 1000L)

        extractBundle(temp, bundle, nowMillis = now)
        assertFalse(stale.exists(), "a folder of an earlier launch stayed")
        assertTrue(starting.exists(), "a folder a launch may be about to load from was deleted")
        assertTrue(unrelated.exists(), "a folder of another program was deleted")
    }

    /**
     * On Linux the temporary folder is shared, so another account can plant a link named like an
     * old folder of ours. The cleanup must not follow it into the folder it names (#885).
     */
    @Test
    fun aPlantedLinkIsNotFollowedByTheCleanup() {
        val victim = Files.createTempDirectory("bundle-extraction-victim").toFile()
        try {
            val precious = File(victim, "precious.txt").apply { writeText("keep") }
            val link = File(temp, "${EXTRACT_PREFIX}-planted").toPath()
            Files.createSymbolicLink(link, victim.toPath())
            val old = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            Files.setLastModifiedTime(link, java.nio.file.attribute.FileTime.fromMillis(old))
            victim.setLastModified(old)
            extractBundle(temp, bundle)
            assertTrue(precious.exists(), "the cleanup followed a link and deleted what it named")
        } finally {
            victim.deleteRecursively()
        }
    }

    /** A folder of the expected name that is open to others is not trusted (#885). */
    @Test
    fun aFolderOthersCanWriteToIsNotUsed() {
        if ("posix" !in temp.toPath().fileSystem.supportedFileAttributeViews()) return
        val first = extractBundle(temp, bundle)
        Files.setPosixFilePermissions(first.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"))
        val second = extractBundle(temp, bundle)
        assertNotEquals(first, second, "a folder anybody can write to was used")
        assertContentEquals(bundle.values.single(), File(second, "libkiteplayer_libass_jni.so").readBytes())
        second.deleteRecursively()
    }
}
