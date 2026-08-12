@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.rename
import platform.posix.remove
import kotlin.test.Test

class MacosBackendContractTest {

    @Test
    fun writesPathFreeBackendContract() = runBlocking {
        val media = getenv("KITEPLAYER_TESTMEDIA")?.toKString()
            ?: error("KITEPLAYER_TESTMEDIA is not set")
        val target = getenv("S1C_TRANSCRIPT_PATH")?.toKString()
            ?: error("S1C_TRANSCRIPT_PATH is not set")
        val temporary = "$target.tmp"
        val bytes = backendContractTranscript(media).encodeToByteArray()
        val file = fopen(temporary, "wb") ?: error("cannot open transcript temporary $temporary")
        try {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file)
                check(written.toInt() == bytes.size) {
                    "short transcript write: $written of ${bytes.size}"
                }
            }
        } finally {
            check(fclose(file) == 0) { "cannot close transcript temporary $temporary" }
        }
        try {
            check(rename(temporary, target) == 0) { "cannot atomically replace transcript $target" }
        } finally {
            remove(temporary)
        }
    }
}
