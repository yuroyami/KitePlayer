@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [block] to its end now. The reader's reads never suspend, because each request finishes
 * before it returns, so nothing here needs a dispatcher.
 */
private fun runNow(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    (outcome ?: error("the block suspended")).getOrThrow()
}

/**
 * The worker's reader reads one version of a file or fails (#855). Each window was its own range
 * request with nothing tying it to the first, so a file replaced during playback, or two edges of a
 * CDN holding different encodes, gave the demuxer the bytes of two files at one set of offsets.
 *
 * Node has no XMLHttpRequest, so each test installs a scripted one that serves a file of 4 MiB
 * whose bytes and validators the test can change between requests.
 */
class SyncHttpFileIdentityTest {
    @BeforeTest fun install() = installScriptedServer()

    @AfterTest fun remove() = removeScriptedServer()

    private suspend fun readAt(io: SyncHttpMediaIo, at: Long, count: Int = 16): ByteArray {
        io.seek(at)
        val into = ByteArray(count)
        assertEquals(count, io.read(into, 0, count))
        return into
    }

    @Test
    fun theSameFileReadsAcrossWindowsAndEveryLaterRequestCarriesIfRange() = runNow {
        serveVersion(version = 1, etag = "\"one\"", modified = null)
        val io = SyncHttpMediaIo("http://media.test/file.mp4", emptyMap(), 5.seconds).also { it.start() }
        try {
            assertEquals(1, readAt(io, 0)[0].toInt())
            assertEquals(1, readAt(io, 3L shl 20)[0].toInt())
            assertEquals(listOf(null, "\"one\""), ifRangeSent().take(2))
        } finally {
            io.close()
        }
    }

    @Test
    fun aChangedEntityTagFailsTheReadInsteadOfJoiningTwoFiles() = runNow {
        serveVersion(version = 1, etag = "\"one\"", modified = null)
        val io = SyncHttpMediaIo("http://media.test/file.mp4", emptyMap(), 5.seconds).also { it.start() }
        try {
            readAt(io, 0)
            serveVersion(version = 2, etag = "\"two\"", modified = null)
            val failure = expectFailure { readAt(io, 3L shl 20) }
            val error = assertIs<PlaybackError.SourceUnavailable>(failure.error)
            assertTrue("changed" in error.toString(), "the error does not say the file changed: $error")
        } finally {
            io.close()
        }
    }

    @Test
    fun aChangedDateOfChangeFailsWhenTheServerGivesNoEntityTag() = runNow {
        serveVersion(version = 1, etag = null, modified = "Mon, 09 Nov 2026 10:00:00 GMT")
        val io = SyncHttpMediaIo("http://media.test/file.mp4", emptyMap(), 5.seconds).also { it.start() }
        try {
            readAt(io, 0)
            readAt(io, 3L shl 20)
            assertEquals("Mon, 09 Nov 2026 10:00:00 GMT", ifRangeSent()[1])
            serveVersion(version = 2, etag = null, modified = "Tue, 10 Nov 2026 10:00:00 GMT")
            expectFailure { readAt(io, 1L shl 20) }
        } finally {
            io.close()
        }
    }

    @Test
    fun aChangedSizeFailsWhenTheServerGivesNoValidator() = runNow {
        serveVersion(version = 1, etag = null, modified = null)
        val io = SyncHttpMediaIo("http://media.test/file.mp4", emptyMap(), 5.seconds).also { it.start() }
        try {
            readAt(io, 0)
            serveVersion(version = 2, etag = null, modified = null, size = 5 shl 20)
            expectFailure { readAt(io, 3L shl 20) }
        } finally {
            io.close()
        }
    }

    /** JavaScript's subarray counts a negative start from the end, so the old read took bytes from the window's tail. */
    @Test
    fun aRangeAnswerThatStartsAfterTheByteAskedForFails() = runNow {
        serveVersion(version = 1, etag = "\"one\"", modified = null)
        val io = SyncHttpMediaIo("http://media.test/file.mp4", emptyMap(), 5.seconds).also { it.start() }
        try {
            readAt(io, 0)
            shiftNextAnswer(10)
            expectFailure { readAt(io, 3L shl 20) }
        } finally {
            io.close()
        }
    }

    private suspend fun expectFailure(block: suspend () -> Unit): PlaybackException {
        try {
            block()
        } catch (failure: PlaybackException) {
            return failure
        }
        fail("the read joined two versions of the file")
    }
}

@JsFun(
    """() => {
      const server = { version: 1, etag: null, modified: null, size: 4 << 20, shift: 0, ifRange: [] };
      globalThis.__kiteServer = server;
      globalThis.__kiteSavedXhr = globalThis.XMLHttpRequest;
      globalThis.XMLHttpRequest = class {
        constructor() { this.headers = {}; this.status = 0; this.response = null; this.responseURL = ''; this.answer = {}; }
        open(method, url) { this.method = method; this.url = url; }
        setRequestHeader(k, v) { this.headers[k.toLowerCase()] = v; }
        send() {
          if (this.method === 'HEAD') { this.status = 200; this.answer = { 'content-length': String(server.size) }; return; }
          server.ifRange.push(this.headers['if-range'] || null);
          const m = /bytes=(\d+)-(\d+)/.exec(this.headers['range'] || '');
          const from = (m ? Number(m[1]) : 0) + server.shift;
          server.shift = 0;
          const to = Math.min(m ? Number(m[2]) : server.size - 1, server.size - 1);
          const body = new Uint8Array(Math.max(0, to - from + 1)).fill(server.version);
          this.status = 206;
          this.response = body.buffer;
          this.responseURL = this.url;
          this.answer = { 'content-type': 'video/mp4', 'content-range': 'bytes ' + from + '-' + to + '/' + server.size };
          if (server.etag) this.answer['etag'] = server.etag;
          if (server.modified) this.answer['last-modified'] = server.modified;
        }
        getAllResponseHeaders() { return Object.keys(this.answer).map((k) => k + ': ' + this.answer[k]).join('\r\n'); }
        getResponseHeader(k) { const v = this.answer[k.toLowerCase()]; return v === undefined ? null : v; }
      };
    }""",
)
private external fun installScriptedServer()

@JsFun("() => { globalThis.XMLHttpRequest = globalThis.__kiteSavedXhr; delete globalThis.__kiteServer; }")
private external fun removeScriptedServer()

@JsFun("(version, etag, modified, size) => { const s = globalThis.__kiteServer; s.version = version; s.etag = etag; s.modified = modified; s.size = size; }")
private external fun serveVersion(version: Int, etag: String?, modified: String?, size: Int)

private fun serveVersion(version: Int, etag: String?, modified: String?) = serveVersion(version, etag, modified, 4 shl 20)

@JsFun("(by) => { globalThis.__kiteServer.shift = by; }")
private external fun shiftNextAnswer(by: Int)

@JsFun("() => globalThis.__kiteServer.ifRange.map((v) => v === null ? '' : v).join('\\n')")
private external fun ifRangeJoined(): String

private fun ifRangeSent(): List<String?> = ifRangeJoined().split('\n').map { it.ifEmpty { null } }
