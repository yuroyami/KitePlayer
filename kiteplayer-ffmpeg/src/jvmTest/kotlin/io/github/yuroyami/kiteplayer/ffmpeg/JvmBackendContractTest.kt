package io.github.yuroyami.kiteplayer.ffmpeg

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

class JvmBackendContractTest {

    @Test
    fun packetPayloadCopiesAreOwned() = runBlocking {
        val media = requireNotNull(System.getProperty("KITEPLAYER_TESTMEDIA")) {
            "KITEPLAYER_TESTMEDIA system property is not set"
        }
        assertOwnedPacketPayload(media)
    }

    @Test
    fun writesPathFreeBackendContract() {
        runBlocking {
            val media = requireNotNull(System.getProperty("KITEPLAYER_TESTMEDIA")) {
                "KITEPLAYER_TESTMEDIA system property is not set"
            }
            val target = requireNotNull(System.getProperty("s1c.transcript.path")) {
                "s1c.transcript.path system property is not set"
            }.let { java.nio.file.Path.of(it) }
            val transcript = backendContractTranscript(media)
            Files.createDirectories(target.parent)
            val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
            try {
                Files.writeString(
                    temporary,
                    transcript,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}
