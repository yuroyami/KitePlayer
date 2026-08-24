package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The 17.5 format conformance matrix: one table, one runner, run on every platform that claims a
 * playback tier. The table IS the claim "plays all formats"; a platform's claim is exactly the
 * transcript this runner leaves.
 *
 * The runner drives the MediaBackend SPI directly (source, decoders, seekToKeyframe) rather than
 * the whole player, deliberately: it needs no output backend, so it runs on a bare iOS simulator
 * spawn host where no audio device can be opened (the S1.b lesson), and on an Android device
 * test without an Activity.
 *
 * Two verdict classes, because capability is measured rather than assumed (D-5):
 * - [MatrixVerdict.MustPlay]: the clip opens, its expected stream kinds are present, frames
 *   decode, a keyframe seek lands and decoding resumes, close is clean. Anything else fails.
 * - [MatrixVerdict.MustSurvive]: every step either succeeds or fails with a typed exception.
 *   The recorded outcome (played or refused, and where) is the measurement. Only a crash or a
 *   hang fails the row. Only the torture rows sit here now, by construction. AV1 used to, because
 *   the phone profile enabled FFmpeg's av1 decoder while vendoring no software AV1 codec, and its
 *   honest outcome was a typed refusal; dav1d is vendored for every target that runs this matrix
 *   now (KC-AV1SW), so AV1 owes a real decode like every other format.
 */
internal enum class MatrixVerdict { MustPlay, MustSurvive }

internal class MatrixRow(
    val clip: String,
    val verdict: MatrixVerdict,
    /** How many video frames the row must decode. Small for the 4K row: formats, not endurance. */
    val videoFrames: Int = 10,
    val audioBuffers: Int = 10,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = true,
    val expectAudioStreams: Int? = null,
    val expectSubtitleStreams: Int? = null,
    val expectRotation: Boolean = false,
    /** Exact chapter round-trip: (startMicros, endMicros, title) per chapter, in order. */
    val expectChapters: List<Triple<Long, Long, String>>? = null,
    /** Decode the first subtitle stream and require at least one non-empty text cue (S4.c). */
    val decodeSubtitleCue: Boolean = false,
)

internal val FORMAT_MATRIX: List<MatrixRow> = listOf(
    MatrixRow("sync1080p30.mp4", MatrixVerdict.MustPlay),
    MatrixRow("baseline.mkv", MatrixVerdict.MustPlay),
    MatrixRow(
        "multitrack.mkv",
        MatrixVerdict.MustPlay,
        expectAudioStreams = 2,
        expectSubtitleStreams = 2,
        decodeSubtitleCue = true,
    ),
    MatrixRow("vp9.webm", MatrixVerdict.MustPlay),
    MatrixRow("mpeg4part2.mp4", MatrixVerdict.MustPlay),
    MatrixRow("hevc4k10.mp4", MatrixVerdict.MustPlay, videoFrames = 5, hasAudio = false),
    MatrixRow("rotated90ccw.mp4", MatrixVerdict.MustPlay, hasAudio = false, expectRotation = true),
    MatrixRow("truevfr720.mp4", MatrixVerdict.MustPlay),
    MatrixRow("tsoffset1400.ts", MatrixVerdict.MustPlay),
    MatrixRow("subbed.mkv", MatrixVerdict.MustPlay, expectSubtitleStreams = 1),
    MatrixRow(
        "chapters.mkv",
        MatrixVerdict.MustPlay,
        expectChapters = listOf(
            Triple(0L, 2_000_000L, "Opening"),
            Triple(2_000_000L, 5_000_000L, "Middle"),
            Triple(5_000_000L, 9_000_000L, "Ending"),
        ),
    ),
    MatrixRow("surround51.mp4", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-aac.m4a", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-mp3.mp3", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-flac.flac", MatrixVerdict.MustPlay, hasVideo = false),
    // Promoted from MustSurvive when dav1d landed on every target this runner reaches. If this row
    // ever refuses again, a tree lost its libdav1d rather than a decoder regressing: check
    // native-libs/deps/<target>/dav1d before reading any Kotlin.
    MatrixRow("av1.mkv", MatrixVerdict.MustPlay),
    MatrixRow("torture-truncated.mp4", MatrixVerdict.MustSurvive, hasAudio = false),
    MatrixRow("torture-garbage.mp4", MatrixVerdict.MustSurvive, hasVideo = false, hasAudio = false),

    // The wide-profile rows (17.4.9 W.b): formats real files arrive in that the narrow
    // editor-era profile could not open or could not decode. Every row below ran RED against
    // the narrow trees before the wide trees existed; the red transcript is in the log. VC-1
    // and RealVideo are the named absences: FFmpeg has no encoders for them, so their rows
    // wait for real sample files instead of pretending.
    MatrixRow("avi-mpeg4.avi", MatrixVerdict.MustPlay),
    MatrixRow("wmv-msmpeg4.wmv", MatrixVerdict.MustPlay),
    MatrixRow("flv-flv1.flv", MatrixVerdict.MustPlay),
    MatrixRow("vob-mpeg2.vob", MatrixVerdict.MustPlay),
    MatrixRow("audio-eac3.mkv", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-dts.mkv", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-truehd.mkv", MatrixVerdict.MustPlay, hasVideo = false),
    MatrixRow("audio-alac.m4a", MatrixVerdict.MustPlay, hasVideo = false),
    // The ass row asserts the subtitle STREAM is seen; decoding its cues is S4.f's, because
    // the text path deliberately speaks SubRip and WebVTT only today.
    MatrixRow("asssubbed.mkv", MatrixVerdict.MustPlay, hasAudio = false, expectSubtitleStreams = 1),
)

/** One row's transcript line. [ok] is the pass/fail; [outcome] is the measured detail. */
internal class MatrixResult(
    val clip: String,
    val verdict: MatrixVerdict,
    val ok: Boolean,
    val outcome: String,
) {
    override fun toString(): String =
        "${if (ok) "PASS" else "FAIL"} ${verdict.name.padEnd(11)} ${clip.padEnd(24)} $outcome"
}

/** Where the matrix media lives on this platform, or null when this platform cannot run it. */
internal expect fun formatMatrixMediaDir(): String?

internal object FormatMatrixRunner {

    /** A row that makes no progress for this long is hanging, which fails both verdicts. */
    private const val ROW_TIMEOUT_MILLIS = 180_000L

    suspend fun runAll(mediaDir: String): List<MatrixResult> = FORMAT_MATRIX.map { row ->
        runRow(mediaDir, row)
    }

    suspend fun runRow(mediaDir: String, row: MatrixRow): MatrixResult = try {
        val outcome = withTimeout(ROW_TIMEOUT_MILLIS) { playRow(mediaDir, row) }
        MatrixResult(row.clip, row.verdict, ok = true, outcome = outcome)
    } catch (hang: TimeoutCancellationException) {
        MatrixResult(row.clip, row.verdict, ok = false, outcome = "HUNG past ${ROW_TIMEOUT_MILLIS} ms")
    } catch (failure: Throwable) {
        when (row.verdict) {
            MatrixVerdict.MustPlay -> MatrixResult(
                row.clip,
                row.verdict,
                ok = false,
                outcome = failure.message ?: failure::class.simpleName ?: "failed",
            )
            // A typed refusal is a legal, measured outcome for a survive row.
            MatrixVerdict.MustSurvive -> MatrixResult(
                row.clip,
                row.verdict,
                ok = true,
                outcome = "refused: ${failure.message ?: failure::class.simpleName}",
            )
        }
    }

    /**
     * The whole row flow. Throws on the first contract violation; returns the transcript detail.
     *
     * Two source contracts shape this, both learned from the source's own checks on the first
     * baseline run: streams are selected ONCE, before the first read, so every stream the row
     * decodes is selected up front and packets are routed to their decoder in one loop; and
     * [KiteCodecSource.seekToKeyframe] returns null BY DESIGN (the container reader does not
     * report a landing; the engine learns it from the first decoded frame), so seek success here
     * is the call returning and decoding resuming, never a non-null landing.
     */
    private suspend fun playRow(mediaDir: String, row: MatrixRow): String {
        val source = KiteCodecSourceFactory().open(MediaItem("$mediaDir/${row.clip}")) as KiteCodecSource
        try {
            val streams = source.streams
            val video = streams.firstOrNull { it.kind == TrackKind.Video && !it.isCoverArt }
            val audio = streams.firstOrNull { it.kind == TrackKind.Audio }

            if (row.hasVideo) checkNotNull(video) { "no video stream in ${row.clip}" }
            if (row.hasAudio) checkNotNull(audio) { "no audio stream in ${row.clip}" }
            row.expectAudioStreams?.let { expected ->
                val counted = streams.count { it.kind == TrackKind.Audio }
                check(counted == expected) { "${row.clip} has $counted audio streams, expected $expected" }
            }
            row.expectSubtitleStreams?.let { expected ->
                val counted = streams.count { it.kind == TrackKind.Subtitle }
                check(counted == expected) { "${row.clip} has $counted subtitle streams, expected $expected" }
            }
            if (row.expectRotation) {
                check(video!!.rotationDegrees != 0) { "${row.clip} reports no rotation" }
            }
            row.expectChapters?.let { expected ->
                val actual = source.chapters.map { chapter ->
                    Triple(
                        chapter.start.inWholeMicroseconds,
                        chapter.end?.inWholeMicroseconds ?: -1L,
                        chapter.title ?: "",
                    )
                }
                check(actual == expected) {
                    "${row.clip} chapters were $actual, expected $expected"
                }
            }

            val wantVideo = row.hasVideo && video != null
            val subtitle = if (row.decodeSubtitleCue) {
                streams.firstOrNull { it.kind == TrackKind.Subtitle }
            } else {
                null
            }
            val wantAudio = row.hasAudio && audio != null
            val selected = buildSet {
                if (wantVideo) add(video!!.index)
                if (wantAudio) add(audio!!.index)
                subtitle?.let { add(it.index) }
            }
            if (selected.isEmpty()) {
                // A torture row may open and expose nothing decodable; surviving open and close
                // is the whole measurement then.
                return "opened, no decodable stream requested"
            }
            source.selectStreams(selected)

            val videoDecoder = if (wantVideo) {
                checkNotNull(
                    source.videoDecoderFactories().firstNotNullOfOrNull { factory ->
                        factory.create(video!!, HwdecPolicy.Auto)
                    },
                ) { "no video decoder accepted ${video!!.codec}" }
            } else {
                null
            }
            val audioDecoder = if (wantAudio) {
                checkNotNull(
                    source.audioDecoderFactories().firstNotNullOfOrNull { factory -> factory.create(audio!!) },
                ) { "no audio decoder accepted ${audio!!.codec}" }
            } else {
                null
            }

            val subtitleDecoder = subtitle?.let { stream ->
                checkNotNull(KiteCodecSubtitleDecoderFactory().create(stream)) {
                    "the text factory refused ${stream.codec}"
                }
            }
            var decodedCueText: String? = null
            try {
                val pass = Progress()
                decodeUntil(
                    source = source,
                    videoIndex = video?.index,
                    audioIndex = audio?.index,
                    videoDecoder = videoDecoder,
                    audioDecoder = audioDecoder,
                    videoQuota = if (wantVideo) row.videoFrames else 0,
                    audioQuota = if (wantAudio) row.audioBuffers else 0,
                    progress = pass,
                    subtitleIndex = subtitle?.index,
                    subtitleDecoder = subtitleDecoder,
                    onCue = { text -> if (decodedCueText == null) decodedCueText = text },
                    requireCue = row.decodeSubtitleCue,
                )
                if (row.decodeSubtitleCue) {
                    checkNotNull(decodedCueText) { "${row.clip} decoded no subtitle cue" }
                }
                if (wantVideo) {
                    check(pass.video >= row.videoFrames) {
                        "${row.clip} decoded ${pass.video} of ${row.videoFrames} video frames"
                    }
                }
                if (wantAudio) {
                    check(pass.audio >= 1) { "${row.clip} decoded no audio" }
                }

                // The seek half: request mid-file and prove decoding resumes past the flush.
                var seekNote = "unseekable"
                val duration = source.duration
                if (source.seekable && duration != null && duration.micros > 0) {
                    val target = Pts(duration.micros / 2)
                    source.seekToKeyframe(target)
                    videoDecoder?.flush(POST_SEEK_GENERATION)
                    audioDecoder?.flush(POST_SEEK_GENERATION)
                    val resumed = Progress()
                    decodeUntil(
                        source = source,
                        videoIndex = video?.index,
                        audioIndex = audio?.index,
                        videoDecoder = videoDecoder,
                        audioDecoder = audioDecoder,
                        videoQuota = if (wantVideo) 1 else 0,
                        audioQuota = if (wantVideo) 0 else 1,
                        progress = resumed,
                    )
                    check(resumed.video + resumed.audio >= 1) {
                        "${row.clip} decoded nothing after the seek to $target"
                    }
                    seekNote = "seek to $target resumed"
                }

                val cueNote = decodedCueText?.let { text -> ", cue '$text'" } ?: ""
            return "video ${pass.video}, audio ${pass.audio}, $seekNote$cueNote"
            } finally {
                videoDecoder?.close()
                audioDecoder?.close()
                subtitleDecoder?.close()
            }
        } finally {
            source.close()
        }
    }

    private class Progress {
        var video: Int = 0
        var audio: Int = 0
    }

    /**
     * One packet loop feeding both decoders until each has met its quota or the file ends.
     * Frames and buffers are closed the moment they are counted, and no receive happens past a
     * met quota, so nothing decoded is ever left unclosed.
     */
    private suspend fun decodeUntil(
        source: KiteCodecSource,
        videoIndex: Int?,
        audioIndex: Int?,
        videoDecoder: io.github.yuroyami.kiteplayer.spi.VideoDecoder?,
        audioDecoder: io.github.yuroyami.kiteplayer.spi.AudioDecoder?,
        videoQuota: Int,
        audioQuota: Int,
        progress: Progress,
        subtitleIndex: Int? = null,
        subtitleDecoder: io.github.yuroyami.kiteplayer.spi.SubtitleDecoder? = null,
        onCue: ((String) -> Unit)? = null,
        requireCue: Boolean = false,
    ) {
        // A ROW THAT WANTS A CUE READS UNTIL IT HAS ONE, and this used to stop at the video and
        // audio quotas instead. Ten video frames at 30 fps is a third of a second and the first cue
        // in multitrack.mkv is at half a second, so whether the row saw a cue came down to where
        // the MUXER happened to interleave the subtitle packet. It passed on the proving Mac with
        // ffmpeg 8.0 and failed in CI with ffmpeg 8.1.2 on a file that is perfectly well formed.
        // The old test was not proving the player decodes cues, it was proving one muxer's
        // interleaving. EOF still ends the loop, so a file with genuinely no cue fails on the
        // checkNotNull below rather than spinning.
        var sawCue = false
        fun done(): Boolean =
            progress.video >= videoQuota &&
                progress.audio >= audioQuota &&
                (!requireCue || sawCue)

        suspend fun drainVideo() {
            if (videoDecoder == null) return
            while (progress.video < videoQuota) {
                val frame = videoDecoder.receive() ?: break
                frame.close()
                progress.video += 1
            }
        }

        suspend fun drainAudio() {
            if (audioDecoder == null) return
            while (progress.audio < audioQuota) {
                val buffer = audioDecoder.receive() ?: break
                buffer.close()
                progress.audio += 1
            }
        }

        while (!done()) {
            val packet = source.readPacket()
            if (packet == null) {
                // End of file: the null packet starts each decoder's drain.
                videoDecoder?.send(null)
                audioDecoder?.send(null)
                drainVideo()
                drainAudio()
                break
            }
            when (packet.streamIndex) {
                subtitleIndex -> if (subtitleDecoder != null) {
                    subtitleDecoder.send(packet)
                    subtitleDecoder.receive().forEach { cue ->
                        if (cue is io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text &&
                            cue.plainText.isNotBlank()
                        ) {
                            sawCue = true
                            onCue?.invoke(cue.plainText)
                        }
                    }
                }
                videoIndex -> if (videoDecoder != null) {
                    // False means full and NOT consumed: drain, then offer the same packet again.
                    while (!videoDecoder.send(packet)) {
                        val frame = videoDecoder.receive() ?: break
                        if (progress.video < videoQuota) progress.video += 1
                        frame.close()
                    }
                }
                audioIndex -> if (audioDecoder != null) {
                    while (!audioDecoder.send(packet)) {
                        val buffer = audioDecoder.receive() ?: break
                        if (progress.audio < audioQuota) progress.audio += 1
                        buffer.close()
                    }
                }
            }
            packet.close()
            drainVideo()
            drainAudio()
        }
    }

    private val POST_SEEK_GENERATION = io.github.yuroyami.kiteplayer.Generation(1)
}
