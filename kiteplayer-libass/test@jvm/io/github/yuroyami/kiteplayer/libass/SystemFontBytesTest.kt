package io.github.yuroyami.kiteplayer.libass

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Each new typesetter reuses the system font bytes the last one read, while the files stay the same (#883). */
class SystemFontBytesTest {

    private val folder: File = createTempDirectory("kite-fonts").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
        SystemFontBytes.forget()
    }

    @Test
    fun theSameFilesAreReadOnceAndAChangedOneIsReadAgain() {
        SystemFontBytes.forget()
        folder.resolve("Roboto-Regular.ttf").writeBytes(ByteArray(1_000) { 1 })
        folder.resolve("NotoSansCJK-Regular.ttc").writeBytes(ByteArray(2_000) { 2 })
        val dirs = listOf(folder.absolutePath)
        val before = SystemFontBytes.reads

        val first = readFontFiles(dirs, budgetBytes = 1_000_000)
        val second = readFontFiles(dirs, budgetBytes = 1_000_000)
        assertEquals(listOf("Roboto-Regular.ttf", "NotoSansCJK-Regular.ttc"), first.map { it.first })
        assertSame(first, second, "a second typesetter got the bytes the first one read")
        assertEquals(before + 1, SystemFontBytes.reads)

        // A font installed or replaced since is a different scan, so it is read from disk.
        folder.resolve("Roboto-Regular.ttf").writeBytes(ByteArray(1_500) { 3 })
        val third = readFontFiles(dirs, budgetBytes = 1_000_000)
        assertEquals(before + 2, SystemFontBytes.reads)
        assertEquals(1_500, third.first().second.size)

        // A budget that picks fewer files is another choice too.
        val small = readFontFiles(dirs, budgetBytes = 1_600)
        assertEquals(listOf("Roboto-Regular.ttf"), small.map { it.first })
        assertEquals(before + 3, SystemFontBytes.reads)
    }
}
