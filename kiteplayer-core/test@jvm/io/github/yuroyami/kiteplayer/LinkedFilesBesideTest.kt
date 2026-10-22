package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.LINKED_FOLDER_LIMIT
import io.github.yuroyami.kiteplayer.internal.linkedCandidates
import io.github.yuroyami.kiteplayer.internal.matroskaFilesBeside
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** The files the player reads for the segments an ordered edition links to (#501). */
class LinkedFilesBesideTest {

    @Test
    fun theMatroskaFilesBesideAFileAreReadInTheOrderOfTheirNames() {
        val folder = Files.createTempDirectory("kiteplayer-linked").toFile()
        try {
            listOf("episode.mkv", "op.mkv", "ed.MKA", "notes.txt", "song.mp4", "subs.mks", "stereo.mk3d").forEach {
                File(folder, it).writeText("")
            }
            File(folder, "folder.mkv").mkdirs()
            val episode = File(folder, "episode.mkv").absolutePath
            assertEquals(
                listOf("ed.MKA", "folder.mkv", "op.mkv", "stereo.mk3d", "subs.mks").map { File(folder, it).absolutePath },
                matroskaFilesBeside(episode),
            )
            // The item's own list comes first, and a file in both is read once.
            val op = File(folder, "op.mkv").absolutePath
            val item = MediaItem(episode, linkedFiles = listOf(LinkedFile("https://cdn.example/ed.mkv"), LinkedFile(op)))
            assertEquals(
                listOf("https://cdn.example/ed.mkv", op) + listOf("ed.MKA", "folder.mkv", "stereo.mk3d", "subs.mks").map { File(folder, it).absolutePath },
                linkedCandidates(item).map { it.uri },
            )
            // A file read through the application's own reader has no folder to look in.
            assertEquals(emptyList(), linkedCandidates(MediaItem(episode, io = MediaIoFactory { error("never opened") })))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun aLargeFolderIsReadOnlyAsFarAsTheLimit() {
        val folder = Files.createTempDirectory("kiteplayer-linked").toFile()
        try {
            repeat(LINKED_FOLDER_LIMIT + 10) { File(folder, "part%04d.mkv".format(it)).writeText("") }
            val found = matroskaFilesBeside(File(folder, "part0000.mkv").absolutePath)
            assertEquals(LINKED_FOLDER_LIMIT, found.size)
            assertEquals(File(folder, "part0001.mkv").absolutePath, found.first())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun theItemsHeadersGoOnlyToItsOwnServer() {
        val item = MediaItem(
            "https://cdn.example/s1/e01.mkv",
            headers = mapOf("Authorization" to "Bearer x"),
            linkedFiles = listOf(LinkedFile("https://cdn.example/s1/op.mkv"), LinkedFile("https://other.example/ed.mkv")),
        )
        assertEquals(
            listOf(mapOf("Authorization" to "Bearer x"), emptyMap()),
            linkedCandidates(item).map { it.headers },
        )
    }
}
