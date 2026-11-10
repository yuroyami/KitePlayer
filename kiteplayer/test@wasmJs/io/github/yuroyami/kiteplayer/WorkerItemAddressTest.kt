@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** A worker that keeps the last message the page posted to it, and answers nothing. */
@JsFun("() => ({ posted: null, postMessage: function (m) { this.posted = m; }, terminate: function () {} })")
private external fun recordingWorker(): JsAny

@JsFun("(w) => w.posted")
private external fun lastPosted(worker: JsAny): JsAny?

/**
 * [relative] read against the page the test runs in, as the browser reads it. Node has no page, and
 * there the player leaves an address as it is, so this does too.
 */
@JsFun("(relative) => typeof document === 'undefined' ? relative : new URL(relative, document.baseURI).href")
private external fun onThePage(relative: String): String

/**
 * Every address of an item is made whole against the page before it crosses to the worker (#894).
 * The thumbnail file and the linked files crossed as given, and in the worker a relative address
 * has no scheme, so neither was ever read.
 */
class WorkerItemAddressTest {
    @Test
    fun aRelativeThumbnailAndLinkedFileCrossAsWholeAddresses() {
        val worker = recordingWorker()
        val player = KitePlayerWorker.onWorkerForTest(worker)
        val item = MediaItem(
            "film.mkv",
            linkedFiles = listOf(LinkedFile("opening.mkv")),
            thumbnails = ThumbnailSource("thumbs.vtt"),
            externalSubtitles = listOf(SubtitleSource("film.srt")),
        )
        val open: suspend () -> Unit = { player.open(item) }
        open.startCoroutine(Continuation(EmptyCoroutineContext) { })
        val call = assertIs<PageMessage.Call>(decodePageMessage(lastPosted(worker)))
        val sent = assertIs<Command.Open>(call.command).item
        assertEquals(onThePage("film.mkv"), sent.uri)
        assertEquals(onThePage("film.srt"), sent.externalSubtitles.single().uri)
        assertEquals(onThePage("thumbs.vtt"), sent.thumbnails?.uri)
        assertEquals(onThePage("opening.mkv"), sent.linkedFiles.single().uri)
    }
}
