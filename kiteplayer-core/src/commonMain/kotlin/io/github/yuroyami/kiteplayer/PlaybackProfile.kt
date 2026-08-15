package io.github.yuroyami.kiteplayer

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A playback preset as data (KPKMP 17.10, KD-6): one named intent compiled into the two places
 * configuration actually goes, the [PlayerConfig] this player reads and the decoder option
 * strings a backend passes to its decoders.
 *
 * The engine stays backend-agnostic, so [decoderOptions] are plain `av_opt_set` strings here;
 * the FFmpeg backend accepts them at construction (`KiteCodecMediaBackend(decoderOptions)`) and
 * applies them through its typed layer. A profile is a VALUE: [toString] prints exactly what it
 * compiles to, which is what the diagnostics dump carries (law 4).
 *
 * ```
 * val profile = PlaybackProfile.Scrubbing
 * val player = KitePlayer.create(
 *     profile.applyTo(PlayerConfig(backends = Backends(
 *         backend = KiteCodecMediaBackend(decoderOptions = profile.decoderOptions),
 *         output = mobileBackends().output,
 *     ))),
 * )
 * ```
 */
public class PlaybackProfile internal constructor(
    public val name: String,
    /** Decoder configuration as `av_opt_set` strings, for the backend's constructor. */
    public val decoderOptions: Map<String, String>,
    /** True when the backend should open its decoders in low-delay shape. */
    public val lowDelayDecode: Boolean,
    private val transform: (PlayerConfig) -> PlayerConfig,
) {
    /** Returns [config] with this profile's player-side choices applied. */
    public fun applyTo(config: PlayerConfig): PlayerConfig = transform(config)

    override fun toString(): String =
        "PlaybackProfile($name, decoderOptions=$decoderOptions, lowDelayDecode=$lowDelayDecode)"

    public companion object {
        /**
         * Fast timeline dragging: the decoder skips the loop filter and every non-keyframe, and
         * late frames drop. The picture is only keyframes; that is the point.
         */
        public val Scrubbing: PlaybackProfile = PlaybackProfile(
            name = "Scrubbing",
            decoderOptions = mapOf(
                "skip_loop_filter" to "all",
                "skip_frame" to "nokey",
            ),
            lowDelayDecode = false,
            transform = { config -> config.copy(frameDrop = FrameDropPolicy.LateOnly) },
        )

        /** Small buffers and low-delay decoding: freshness over smoothness. */
        public val LowLatency: PlaybackProfile = PlaybackProfile(
            name = "LowLatency",
            decoderOptions = emptyMap(),
            lowDelayDecode = true,
            transform = { config ->
                config.copy(
                    buffer = config.buffer.copy(
                        readyDuration = 200.milliseconds,
                        readyPackets = 5,
                        softTarget = 1.seconds,
                        videoFrameQueue = 2,
                    ),
                )
            },
        )

        /** Prefer hardware decode and relax the reporting intervals. */
        public val Battery: PlaybackProfile = PlaybackProfile(
            name = "Battery",
            decoderOptions = emptyMap(),
            lowDelayDecode = false,
            transform = { config ->
                config.copy(
                    hardwareDecode = HwdecPolicy.Auto,
                    progressInterval = 500.milliseconds,
                    statsInterval = 2.seconds,
                )
            },
        )
    }
}
