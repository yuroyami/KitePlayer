package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.FFmpegIdentity
import io.github.yuroyami.kiteffmpeg.FFmpegLibraryIdentity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An incompatible FFmpeg runtime has to reach the caller as a typed [PlaybackError], and as the RIGHT
 * one.
 *
 * **Why the rejection is synthesised here rather than provoked.** KiteFFmpeg's gate compares the FFmpeg
 * headers its C layer was compiled against with the runtime it is linked to, and in this process those
 * agree, because the archive this test links was built against the headers of the FFmpeg it loads. Making
 * them disagree needs a second FFmpeg install or a doctored compile, and the doctored compile is exactly
 * what `KiteFFmpeg/native/kitecodec-c/tests/test_identity.c` does: five copies of the gate against five
 * shim header trees, one case per verdict, which is where the evidence that the gate FIRES lives.
 *
 * What this file owns is the other half, and it is the half a doctored C compile cannot reach: that the
 * rejection KiteFFmpeg throws arrives at a KitePlayer caller as `PlaybackError.ConfigurationInvalid` and
 * not as `SourceUnavailable`, that it carries the whole report, and that no other KiteFFmpeg failure is
 * caught by the same net. The identity value is built through KiteFFmpeg's own public constructors, so a
 * change to that shape breaks this test at compile time rather than letting it drift.
 */
class FFmpegRuntimeRejectionTest {

    private fun library(
        name: String,
        headerMajor: Int,
        runtimeMajor: Int,
        verdict: String,
    ) = FFmpegLibraryIdentity(
        name = name,
        headerMajor = headerMajor, headerMinor = 8, headerMicro = 100,
        runtimeMajor = runtimeMajor, runtimeMinor = 8, runtimeMicro = 100,
        verdict = verdict,
    )

    private fun rejectingIdentity(bypassed: Boolean = false) = FFmpegIdentity(
        status = if (bypassed) 0 else -1,
        bypassed = bypassed,
        bypassedStatus = if (bypassed) -1 else 0,
        cAbiVersion = "1.0",
        libraries = listOf(
            library("libavutil", headerMajor = 59, runtimeMajor = 60, verdict = "major mismatch"),
            library("libavcodec", headerMajor = 62, runtimeMajor = 62, verdict = "ok"),
        ),
        configurationsAgree = true,
        configurationsDisagreed = emptyList(),
        buildFFmpegRef = "n8.0",
        buildLicenseFlavour = "lgpl",
        buildProvisioningDir = "/opt/homebrew/lib",
        runtimeVersionInfo = "8.0",
        runtimeLicense = "GPL version 3 or later",
        provisioning = "Link the FFmpeg build the headers came from, or rebuild KiteFFmpeg.",
    )

    @Test
    fun aRejectionBecomesConfigurationInvalidAndNotSourceUnavailable() {
        val failure = assertFailsWith<PlaybackException> {
            mappingFFmpegRuntimeRejection<Unit> {
                throw FFmpegException(FFmpegError.IncompatibleFFmpegRuntime(rejectingIdentity()))
            }
        }
        // ConfigurationInvalid, because nothing about this failure depends on the media: every file fails,
        // the next one will too, and retrying is pointless. SourceUnavailable would say the bytes could
        // not be reached, which would send the reader to look at the file.
        // ConfigurationInvalid and SourceUnavailable are sibling data classes, so the compiler already
        // knows one is never the other; asserting it would be a check it rejects as always true. The
        // assertIs below is the whole distinction, and the comment above is why it matters.
        val error = assertIs<PlaybackError.ConfigurationInvalid>(failure.error)
        assertContains(error.detail, "the linked FFmpeg does not match")
        assertContains(error.detail, "libavutil headers 59.8.100 against runtime 60.8.100")
        assertContains(error.detail, "major mismatch")
        // The whole report travels with it, so a log line or a bug report is complete on its own.
        assertContains(error.detail, "built for FFmpeg n8.0")
        assertContains(error.detail, "GPL version 3 or later")
        assertContains(error.detail, "rebuild KiteFFmpeg")
        // And the message a caller sees is the error's own sentence.
        assertContains(failure.message.orEmpty(), "cannot be built as configured")
    }

    /**
     * A bypassed gate is still reported, and reported first.
     *
     * The diagnostic escape hatch turns a rejection into a warning so that a false rejection is not an
     * outage inside an application that cannot patch KiteFFmpeg. The condition attached to it is that using
     * it is never quiet, and this is the KitePlayer end of that condition: an investigation that does not
     * know the numbers were judged unsafe and used anyway starts from the wrong place.
     */
    @Test
    fun aBypassedGateSaysSoBeforeAnythingElse() {
        val detail = incompatibleRuntimeDetail(
            FFmpegError.IncompatibleFFmpegRuntime(rejectingIdentity(bypassed = true)),
        )
        assertContains(detail, "the identity gate was bypassed")
        assertContains(detail, "judged unsafe and used anyway")
        assertContains(detail, "BYPASSED")
    }

    @Test
    fun aMixedInstallWithNoVersionProblemStillGetsASummary() {
        // configurationsDisagreed is the only evidence when every version triple agrees, so the summary
        // has to fall back to it rather than reading as an empty sentence.
        val identity = FFmpegIdentity(
            status = -3,
            bypassed = false,
            bypassedStatus = 0,
            cAbiVersion = "1.0",
            libraries = listOf(library("libavutil", 60, 60, "ok")),
            configurationsAgree = false,
            configurationsDisagreed = listOf("libavfilter"),
            buildFFmpegRef = "n8.0",
            buildLicenseFlavour = "lgpl",
            buildProvisioningDir = "/opt/homebrew/lib",
            runtimeVersionInfo = "8.0",
            runtimeLicense = "GPL version 3 or later",
            provisioning = "Link the FFmpeg build the headers came from.",
        )
        val detail = incompatibleRuntimeDetail(FFmpegError.IncompatibleFFmpegRuntime(identity))
        assertContains(detail, "the six configure lines disagree: libavfilter")
    }

    @Test
    fun everyOtherKiteFFmpegFailurePassesThroughUntouched() {
        // The net must catch exactly one thing. A media error rethrown as a configuration error would be
        // worse than no mapping at all, because it would be confidently wrong.
        val original = FFmpegException(FFmpegError.FileNotFound(-2, "No such file or directory"))
        val caught = assertFailsWith<FFmpegException> {
            mappingFFmpegRuntimeRejection<Unit> { throw original }
        }
        assertEquals(original, caught)
        assertIs<FFmpegError.FileNotFound>(caught.error)
    }

    @Test
    fun aSuccessfulCallIsNotDisturbed() {
        assertEquals(42, mappingFFmpegRuntimeRejection { 42 })
    }

    /**
     * The runtime this test suite is linked against is compatible, so the real gate lets a real open
     * through. Without this, every assertion above could pass while the gate refused everything.
     */
    @Test
    fun theRealRuntimeIsAcceptedSoTheGateIsNotRefusingEverything() {
        assertTrue(FFmpeg.identity.isAcceptable, FFmpeg.identity.describe())
        val failure = assertFailsWith<FFmpegException> {
            mappingFFmpegRuntimeRejection {
                io.github.yuroyami.kiteffmpeg.MediaSource.open("/definitely/not/a/media/file.mp4")
            }
        }
        // A missing file reports a missing file. The identity gate ran first and said nothing.
        assertIs<FFmpegError.FileNotFound>(failure.error)
    }
}
