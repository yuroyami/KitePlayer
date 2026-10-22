package io.github.yuroyami.kiteplayer.io

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private fun tempFileOf(bytes: ByteArray): File =
    File.createTempFile("kiteplayer-io", ".bin").apply {
        deleteOnExit()
        writeBytes(bytes)
    }

/**
 * The asset door's descriptor path on a real device: an [AssetFileDescriptor] whose window sits
 * between 1,000 junk bytes on each side, which is the shape of an asset inside an APK.
 */
class AssetWindowDeviceTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory {
        val file = tempFileOf(JUNK + bytes + JUNK)
        return MediaIoFactory {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            AssetFileDescriptor(descriptor, JUNK.size.toLong(), bytes.size.toLong()).toMediaIo()
        }
    }

    private companion object {
        val JUNK = ByteArray(1_000) { 0x5A }
    }
}

/**
 * The content door through a real ContentResolver. A file URI is the one scheme the resolver opens
 * without a provider, and it answers with an unknown length, like many providers do.
 */
class ContentUriDeviceTest : MediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        return MediaIo.ofUri(resolver, Uri.fromFile(tempFileOf(bytes)))
    }
}

/** A provider that answers with a pipe. The door plays it forward only. */
class PipeDeviceTest : ForwardMediaIoContractTest() {
    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIoFactory {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true) {
            // A reader that closes early breaks the pipe. That ends this writer, and nothing else.
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { it.write(bytes) } }
        }
        AssetFileDescriptor(readSide, 0, AssetFileDescriptor.UNKNOWN_LENGTH).toMediaIo()
    }

    @Test
    fun aPipeHasNoSizeAndCannotSeek() = runTest {
        factory(byteArrayOf(1)).open().use { reader ->
            assertNull(reader.size)
            assertFalse(reader.seekable)
            assertFailsWith<UnsupportedOperationException> { reader.seek(0) }
        }
    }
}

/** A pipe whose writer stays open after the bytes it sent. A cancelled read must end (#276). */
class StalledPipeDeviceTest {

    @Test
    fun aCancelledReadOnAPipeWithAnOpenWriterEnds() = runBlocking {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
        writer.write(ByteArray(100) { it.toByte() })
        writer.flush()
        val reader = AssetFileDescriptor(readSide, 0, AssetFileDescriptor.UNKNOWN_LENGTH).toMediaIo()
        val buffer = ByteArray(64)
        var received = 0
        while (received < 100) received += reader.read(buffer, 0, buffer.size)

        val read = launch(Dispatchers.Default) { reader.read(buffer, 0, buffer.size) }
        delay(200)
        assertTrue(read.isActive, "the read waits for bytes the writer never sends")
        withTimeout(2.seconds) { read.cancelAndJoin() }
        assertFailsWith<IllegalStateException> { reader.read(buffer, 0, buffer.size) }
        reader.close()
        writer.close()
    }
}
