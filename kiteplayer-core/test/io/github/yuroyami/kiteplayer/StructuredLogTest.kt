@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Logging that a machine can read, and that cannot leak a credential.
 *
 * The plain sink flattens everything into a sentence, so a structured backend had to parse the
 * warning's type and its stream index back out with a regular expression. And a warning quotes the
 * URI it failed on, which quotes the query string, which is where tokens and signatures live: logs
 * get pasted into issue trackers, so the safe default is the one that cannot carry a secret.
 *
 * Both are the same seam. Installing either sink replaces the other, because two live sinks would
 * mean deciding which one an engine line belongs to, and there is no honest answer to that.
 */
class StructuredLogTest {

    private class Recorder : KiteLog.StructuredSink {
        val events = mutableListOf<Triple<String, String, Map<String, String>>>()
        override fun event(tag: String, message: String, fields: Map<String, String>) {
            events += Triple(tag, message, fields)
        }
    }

    private class PlainRecorder : KiteLog.Sink {
        val lines = mutableListOf<String>()
        override fun log(tag: String, message: String) {
            lines += message
        }
    }

    @AfterTest
    fun uninstall() {
        KiteLog.install(null)
        KiteLog.redactUris = true
    }

    @Test
    fun `a warning arrives with its type and its own values`() = runTest {
        val recorder = Recorder()
        KiteLog.installStructured(recorder)

        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        // A warning with something worth querying on: the track it deselected and why.
        harness.core.post(
            io.github.yuroyami.kiteplayer.internal.CoreCommand.SelectTrack(
                TrackKind.Subtitle,
                TrackId(-99),
                kotlinx.coroutines.CompletableDeferred(),
            ),
        )
        harness.run(100.milliseconds)
        harness.close()

        assertTrue(recorder.events.isNotEmpty(), "no warning was logged at all")
        val fields = recorder.events.map { it.third }
        assertTrue(
            fields.all { "warning" in it },
            "a logged warning did not name its own type: $fields",
        )
        assertEquals("kiteplayer", recorder.events.first().first, "the tag changed")
    }

    @Test
    fun `a URI in a message is reduced to its filename`() {
        val recorder = Recorder()
        KiteLog.installStructured(recorder)
        KiteLog.log("test", "cannot open https://cdn.example/v/movie.mkv?token=SECRET: 404")

        val message = recorder.events.single().second
        assertTrue("movie.mkv" in message, "the filename was lost: $message")
        assertTrue("SECRET" !in message, "the token survived redaction: $message")
        assertTrue("cdn.example" !in message, "the host survived redaction: $message")
    }

    @Test
    fun `field values are redacted too`() {
        // The message is not the only place a URI appears. A field carrying one would otherwise be
        // the leak the message redaction was added to prevent.
        val recorder = Recorder()
        KiteLog.installStructured(recorder)
        KiteLog.log("test", "failed", mapOf("uri" to "https://cdn.example/a.mkv?token=SECRET"))

        val value = recorder.events.single().third.getValue("uri")
        assertEquals("a.mkv", value)
    }

    @Test
    fun `redaction can be turned off for an application that logs local paths`() {
        val recorder = Recorder()
        KiteLog.installStructured(recorder)
        KiteLog.redactUris = false
        KiteLog.log("test", "cannot open https://cdn.example/v/movie.mkv?token=SECRET")

        assertTrue("SECRET" in recorder.events.single().second, "redaction stayed on after being turned off")
    }

    @Test
    fun `a plain sink still receives the fields rather than losing them`() {
        // The simpler seam, not the less informed one.
        val plain = PlainRecorder()
        KiteLog.install(plain)
        KiteLog.log("test", "something happened", mapOf("warning" to "TrackDeselected", "track" to "stream3"))

        val line = plain.lines.single()
        assertTrue("something happened" in line, "the message was lost: $line")
        assertTrue("warning=TrackDeselected" in line, "the type was lost: $line")
        assertTrue("track=stream3" in line, "the value was lost: $line")
    }

    @Test
    fun `installing one kind of sink replaces the other`() {
        val plain = PlainRecorder()
        val structured = Recorder()
        KiteLog.install(plain)
        KiteLog.installStructured(structured)
        KiteLog.log("test", "one")
        assertTrue(plain.lines.isEmpty(), "the plain sink was still live after a structured one replaced it")
        assertEquals(1, structured.events.size)

        KiteLog.install(plain)
        KiteLog.log("test", "two")
        assertEquals(1, structured.events.size, "the structured sink was still live after a plain one replaced it")
        assertEquals(1, plain.lines.size)
    }

}
