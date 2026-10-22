package io.github.yuroyami.kiteplayer

import org.junit.AssumptionViolatedException
import kotlin.test.fail

/**
 * True in a job that generated the test clips, which says so with `KITEPLAYER_REQUIRE_TESTMEDIA=1`.
 * There a missing clip or a missing `ffmpeg` fails the test instead of skipping it, so a broken
 * clip step cannot hide behind a green run (#419). The same helper sits in each module's JVM tests.
 */
internal val testMediaRequired: Boolean = System.getenv("KITEPLAYER_REQUIRE_TESTMEDIA") == "1"

/**
 * Returns [value], or, when it is null, skips the test with [missing] as the reason, so the report
 * counts the test as skipped rather than passed (#419). Where [testMediaRequired] holds, the test
 * fails with that reason instead. For what the clip step provides: a clip, or the `ffmpeg` that made
 * them. A missing device, such as an audio mixer, is an ordinary assumption and skips everywhere.
 */
internal fun <T : Any> requireTestMedia(value: T?, missing: String): T {
    if (value != null) return value
    if (testMediaRequired) fail("$missing, and KITEPLAYER_REQUIRE_TESTMEDIA=1 says this job generated the clips")
    throw AssumptionViolatedException(missing)
}

/** [requireTestMedia] for a condition: skips, or fails where the clips were generated, unless [present]. */
internal fun requireTestMedia(present: Boolean, missing: String) {
    requireTestMedia(if (present) Unit else null, missing)
}
