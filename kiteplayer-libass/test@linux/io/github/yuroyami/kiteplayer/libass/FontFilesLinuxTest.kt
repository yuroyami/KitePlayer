@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.symlink
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The native Linux font walk enters a folder that is a link (#886). It entered only what readdir
 * reported as DT_DIR, and a link reports DT_LNK, so a font in a linked folder was never loaded,
 * while the JVM's walk, which follows links, found it.
 */
class FontFilesLinuxTest {

    @Test
    fun aFontInALinkedFolderIsFound() {
        val root = "/tmp/kiteplayer-fonts-${getpid()}"
        val real = "$root-real"
        mkdir(root, 0x1EDu)
        mkdir(real, 0x1EDu)
        val font = "$real/linked.ttf"
        fopen(font, "wb")?.let { file ->
            fputs("not really a font, but a file of the right name", file)
            fclose(file)
        }
        symlink(real, "$root/linked")
        try {
            val found = readFontFiles(listOf(root), budgetBytes = 1_000_000).map { it.first }
            assertEquals(listOf("linked.ttf"), found, "the walk did not enter the linked folder")
        } finally {
            unlink("$root/linked")
            unlink(font)
            rmdir(real)
            rmdir(root)
        }
    }
}
