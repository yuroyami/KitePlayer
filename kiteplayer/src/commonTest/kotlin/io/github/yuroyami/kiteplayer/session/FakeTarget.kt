package io.github.yuroyami.kiteplayer.session

/**
 * A player that records what was asked of it.
 *
 * Private vars behind read-only overrides: a `var volume` here would compile to a JVM `setVolume`
 * and clash with the interface call of the same name.
 */
internal class FakeTarget(
    playing: Boolean = true,
    volume: Float = 1f,
    videoEnabled: Boolean = true,
) : SessionTarget {
    val calls = mutableListOf<String>()

    private var playingNow = playing
    private var volumeNow = volume
    private var videoEnabledNow = videoEnabled
    private var transportCount = 0L

    override val playing: Boolean get() = playingNow
    override val transportMark: Long get() = transportCount

    /** A press the listener made, outside every guard: the lock screen, a headset, the app. */
    fun userPlays() {
        transportCount++
        playingNow = true
    }

    fun userPauses() {
        transportCount++
        playingNow = false
    }
    /** The listener's own volume. The guards never write it. */
    val volume: Float get() = volumeNow
    override val videoEnabled: Boolean get() = videoEnabledNow

    override fun play() {
        transportCount++
        playingNow = true
        calls += "play"
    }

    override fun pause() {
        transportCount++
        playingNow = false
        calls += "pause"
    }

    /** A volume change the listener made, outside every guard. */
    fun userSetsVolume(value: Float) {
        volumeNow = value
    }

    /** The duck multiplier the guard set, 1 when none. */
    var duckLevel: Float = 1f
        private set

    override fun setDuckLevel(level: Float) {
        duckLevel = level
        calls += "duck $level"
    }

    override fun setVideoEnabled(enabled: Boolean) {
        videoEnabledNow = enabled
        calls += "video $enabled"
    }
}
