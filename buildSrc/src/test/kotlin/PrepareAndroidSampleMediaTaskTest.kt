package io.github.yuroyami.kiteplayer.buildtools

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1.c.6 step 3's pins: missing input, byte equality, rerun after a content change, and no
 * partial destination after an injected failure. The SHA line is a lifecycle log, proved by the
 * digest of the copied bytes matching the source rather than by scraping logger output.
 */
class PrepareAndroidSampleMediaTaskTest {

    private fun task(source: File, outDir: File): PrepareAndroidSampleMediaTask {
        val project = ProjectBuilder.builder().build()
        val t = project.tasks.register("prepareTestMedia", PrepareAndroidSampleMediaTask::class.java).get()
        t.sourceMedia.set(source)
        t.outputDirectory.set(outDir)
        return t
    }

    @Test
    fun `a missing or empty input fails loudly naming the producer`() {
        val dir = createTempDir("kp-sample-media")
        val missing = File(dir, "absent.mp4")
        assertFailsWith<GradleException> { task(missing, File(dir, "out")).prepare() }
        val empty = File(dir, "empty.mp4").apply { writeBytes(ByteArray(0)) }
        val failure = assertFailsWith<GradleException> { task(empty, File(dir, "out")).prepare() }
        assertTrue("testmedia.sh" in (failure.message ?: ""), "the failure names the producer")
    }

    @Test
    fun `the copy is byte identical and reruns after the content changes`() {
        val dir = createTempDir("kp-sample-media")
        val source = File(dir, "clip.mp4").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val out = File(dir, "out")
        val t = task(source, out)
        t.prepare()
        val copy = File(out, "clip.mp4")
        assertContentEquals(source.readBytes(), copy.readBytes())
        source.writeBytes(ByteArray(2048) { (it * 3).toByte() })
        t.prepare()
        assertContentEquals(source.readBytes(), copy.readBytes(), "a changed source re-copies")
    }

    @Test
    fun `an injected failure leaves no partial destination`() {
        val dir = createTempDir("kp-sample-media")
        val source = File(dir, "clip.mp4").apply { writeBytes(ByteArray(1024) { 7 }) }
        val out = File(dir, "out").apply { mkdirs() }
        /* Injection: a directory squatting on the destination name makes the rename fail after
         * the copy, which is the transactional path's worst moment. */
        val squatter = File(out, "clip.mp4").apply { mkdirs(); File(this, "inner").writeText("x") }
        assertFailsWith<GradleException> { task(source, out).prepare() }
        assertFalse(File(out, "clip.mp4.tmp").exists(), "no temporary residue survives a failure")
        assertTrue(squatter.isDirectory, "the failure did not half-replace the destination")
    }
}
