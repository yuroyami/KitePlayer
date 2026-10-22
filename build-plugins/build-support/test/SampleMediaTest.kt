/* Copyright 2026 yuroyami. Apache License, Version 2.0 (see LICENSE). */

package io.github.yuroyami.kiteplayer.build

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guarantees of the sample media copy: missing input, byte equality, a new copy after a content
 * change, and no partial destination after an injected failure.
 */
class SampleMediaTest {

    private val producer = "The clip comes from scripts/testmedia.sh."

    @Test
    fun `a missing or empty input fails loudly naming the producer`() {
        val dir = createTempDirectory("kp-sample-media").toFile()
        val missing = File(dir, "absent.mp4")
        assertFailsWith<BuildFailure> { syncMediaFiles(listOf(missing), File(dir, "out"), producer) }
        val empty = File(dir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
        val failure = assertFailsWith<BuildFailure> { syncMediaFiles(listOf(empty), File(dir, "out"), producer) }
        assertTrue("testmedia.sh" in (failure.message ?: ""), "the failure names the producer")
    }

    @Test
    fun `the copy is byte identical and reruns after the content changes`() {
        val dir = createTempDirectory("kp-sample-media").toFile()
        val source = File(dir, "clip.mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val out = File(dir, "out")
        syncMediaFiles(listOf(source), out, producer)
        val copy = File(out, "clip.mp4")
        assertContentEquals(source.readBytes(), copy.readBytes())
        source.writeBytes(ByteArray(2048) { (it * 3).toByte() })
        syncMediaFiles(listOf(source), out, producer)
        assertContentEquals(source.readBytes(), copy.readBytes(), "a changed source re-copies")
    }

    @Test
    fun `an injected failure leaves no partial destination`() {
        val dir = createTempDirectory("kp-sample-media").toFile()
        val source = File(dir, "clip.mp4").apply { writeBytes(ByteArray(1024) { 7 }) }
        val out = File(dir, "out").apply { mkdirs() }
        // A directory squatting on the destination name makes the rename fail after the copy.
        val squatter = File(out, "clip.mp4").apply { mkdirs(); File(this, "inner").writeText("x") }
        assertFailsWith<BuildFailure> { syncMediaFiles(listOf(source), out, producer) }
        assertFalse(File(out, "clip.mp4.tmp").exists(), "no temporary residue survives a failure")
        assertTrue(squatter.isDirectory, "the failure did not half-replace the destination")
    }
}
