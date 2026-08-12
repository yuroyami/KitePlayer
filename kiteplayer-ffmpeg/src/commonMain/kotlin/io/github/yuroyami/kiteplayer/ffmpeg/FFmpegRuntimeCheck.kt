package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kitecodec.FFmpegError
import io.github.yuroyami.kitecodec.FFmpegException

/**
 * Turns KiteCodec's FFmpeg identity rejection into a typed [PlaybackError].
 *
 * **What is being reported.** KiteCodec's C layer compares the FFmpeg headers it was compiled against
 * with the FFmpeg runtime it is linked to, once per process, before anything allocates. In the direction
 * that matters, older headers against a newer runtime, every symbol resolves and the link succeeds while
 * struct field offsets are wrong, and the failure that follows is a wrong value read and then a crash
 * inside FFmpeg's own code. KiteCodec refuses to run on a combination it knows is unsafe and throws
 * `FFmpegError.IncompatibleFFmpegRuntime` carrying a report with both version columns.
 *
 * **Why it becomes [PlaybackError.ConfigurationInvalid] and not [PlaybackError.SourceUnavailable].**
 * Without this mapping the engine's `classify` would see an unrecognised `FFmpegException` from
 * `backend.open` and report `SourceUnavailable`, which says "the bytes could not be reached". Nothing is
 * wrong with the bytes. Nothing about this failure depends on the media at all: every file fails, the
 * next file will fail too, and retrying is pointless. It is the player being asked to exist against a
 * runtime it cannot use, which is exactly what `ConfigurationInvalid` is for, and telling the two apart
 * is the difference between an application retrying for ever and an application showing its user
 * something true.
 *
 * The report's own text travels in the message, so the whole diagnosis, both version columns for all six
 * libraries, both licence strings and one actionable sentence, reaches a log or a bug report without
 * anyone having to reach back into KiteCodec for it.
 */
internal inline fun <T> mappingFFmpegRuntimeRejection(block: () -> T): T = try {
    block()
} catch (rejection: FFmpegException) {
    val error = rejection.error
    if (error is FFmpegError.IncompatibleFFmpegRuntime) {
        throw PlaybackException(PlaybackError.ConfigurationInvalid(incompatibleRuntimeDetail(error)))
    }
    // Every other FFmpegException is about the media or the operation, and belongs to whoever catches it.
    throw rejection
}

/**
 * The one-line summary plus the whole report, which is what a `ConfigurationInvalid` detail should carry.
 *
 * The summary comes first so a single-line log is still useful, and the report follows so a bug report is
 * complete. When the report says the gate was bypassed, that is said first of all: a bypassed gate means
 * the numbers below were judged unsafe and then ignored on purpose, and an investigation that does not
 * know that starts from the wrong place.
 */
internal fun incompatibleRuntimeDetail(error: FFmpegError.IncompatibleFFmpegRuntime): String {
    val identity = error.identity
    val summary = identity.problems
        .joinToString { "${it.name} headers ${it.headerVersion} against runtime ${it.runtimeVersion} (${it.verdict})" }
        .ifEmpty { "the six configure lines disagree: ${identity.configurationsDisagreed.joinToString()}" }
    return buildString {
        append("the linked FFmpeg does not match the one KiteCodec was built against: ")
        append(summary)
        if (identity.bypassed) {
            append(". NOTE: the identity gate was bypassed by an environment variable, so this runtime ")
            append("was judged unsafe and used anyway")
        }
        append('\n')
        append(identity.describe())
    }
}
