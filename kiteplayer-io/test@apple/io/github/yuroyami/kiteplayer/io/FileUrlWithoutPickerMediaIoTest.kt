package io.github.yuroyami.kiteplayer.io

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoFactory
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSURL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The URL door over plain file URLs. No system picker made these URLs, so the security scope is
 * started and stopped here only in the form that needs no scope. A picked file is a device step.
 */
class FileUrlWithoutPickerMediaIoTest : MediaIoContractTest() {
    private val files = TempFiles()

    override fun factory(bytes: ByteArray): MediaIoFactory = MediaIo.ofUrl(NSURL.fileURLWithPath(files.create(bytes)))

    @AfterTest
    fun deleteFiles() = files.deleteAll()

    @Test
    fun aUrlThatIsNotAFileIsRefusedAtOpen() = runTest {
        val url = "https://example.com/movie.mkv"
        val failure = assertFailsWith<MediaIoException> { MediaIo.ofUrl(NSURL(string = url)).open() }
        assertTrue(url in failure.message.orEmpty(), failure.message)
    }
}
