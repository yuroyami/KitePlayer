package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.PlayerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.StandardSocketOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The whole player on each live protocol over the loopback, against the `ffmpeg` command line as
 * the sender (#395). The marker clip beeps and flashes together at the start of every second, and
 * each beep's distance from its flash, as heard by [PacedOutput] and shown by [FlashRecorder], must
 * stay inside the window of EBU R37, which is the broadcast tolerance for sound against picture:
 * no more than 40 ms ahead and no more than 60 ms behind. The same clip read from a file is held
 * to the same window, so a stream is in sync exactly when the file is. The window holds from the
 * second second of play: the beeps of the first are printed and not held to it, because that is
 * when the sound and the picture first meet, and the file's first beep sits apart from the rest
 * too.
 *
 * The rest stop the sender part way through, by silence or by hanging up, and check that the
 * playback ends within a stated time instead of waiting for a sender that is gone.
 *
 * Without `ffmpeg` on PATH every test here skips.
 */
class LivePlaybackTest {

    @Test
    fun theMarkerClipReadFromAFileIsInSync() = live { clip ->
        playsInSync("file", MediaItem(clip.absolutePath))
    }

    @Test
    fun anRtmpFeedPlaysInSync() = live { clip ->
        val url = "rtmp://127.0.0.1:${freePort()}/live/kite"
        sender(clip, "rtmp", listOf("-f", "flv", "-listen", "1", "-timeout", "15", url)).use {
            playsInSync("rtmp", MediaItem(url), retryOpen = true)
        }
    }

    @Test
    fun aUdpStreamPlaysInSync() = live { clip ->
        val port = freePort()
        playsInSync("udp", MediaItem("udp://127.0.0.1:$port")) {
            sender(clip, "udp", listOf("-f", "mpegts", "udp://127.0.0.1:$port?pkt_size=1316"))
        }
    }

    /** A multicast group on an interface that loops multicast back to this host, where there is one. */
    @Test
    fun aUdpMulticastStreamPlaysInSync() = live { clip ->
        val local = multicastLoopAddress() ?: return@live println("SKIP: no interface here loops multicast back to this host")
        val port = freePort()
        playsInSync("udp multicast on $local", MediaItem("udp://$GROUP:$port?localaddr=$local")) {
            sender(clip, "multicast", listOf("-f", "mpegts", "udp://$GROUP:$port?localaddr=$local&ttl=1&pkt_size=1316"))
        }
    }

    @Test
    fun anRtpSessionThatAnSdpFileDescribesPlaysInSync() = live { clip ->
        val video = evenPort()
        val audio = video + 2
        val sdp = File(MarkerClip.dir, "session-$video.sdp")
        // A sender with nobody listening yet prints the description, which the real one repeats.
        // It prints one as each stream starts, and only the last names both; the file that
        // -sdp_file writes holds the first.
        sender(clip, "sdp", rtpOutputs(video, audio), input = listOf("-t", "0.2")).use { describer ->
            check(describer.waitFor(30) == 0) { "no session description: ${describer.logText()}" }
            val description = describer.log.readText().substringAfterLast("SDP:\n", "").substringBefore("\n\n")
            check("m=audio" in description) { "the session description has no sound: ${describer.logText()}" }
            sdp.writeText(description.trim() + "\n")
        }
        playsInSync("sdp", MediaItem(sdp.absolutePath)) { sender(clip, "rtp", rtpOutputs(video, audio)) }
    }

    @Test
    fun anRtspCameraOverTcpPlaysInSync() = rtspPlaysInSync("tcp")

    @Test
    fun anRtspCameraOverUdpPlaysInSync() = rtspPlaysInSync("udp")

    @Test
    fun aRawTcpStreamPlaysInSync() = live { clip ->
        val port = freePort()
        sender(clip, "tcp", listOf("-f", "mpegts", "tcp://127.0.0.1:$port?listen=1&listen_timeout=15000")).use {
            playsInSync("tcp", MediaItem("tcp://127.0.0.1:$port"), retryOpen = true)
        }
    }

    /**
     * A sender that pushes at the pace it plays opens as a real-time source, and a file or a raw
     * TCP stream, whose sender TCP holds back to the reader's pace, does not.
     */
    @Test
    fun onlyAPushedStreamOpensAsRealTime() = live { clip ->
        suspend fun realTime(item: MediaItem, retry: Boolean = false): Boolean {
            val started = TimeSource.Monotonic.markNow()
            while (true) {
                try {
                    return KiteFFmpegMediaBackend().open(item).use { it.source.realTime }
                } catch (refused: FFmpegException) {
                    // The backend's own open, which the engine would wrap.
                    if (!retry || started.elapsedNow() > 10.seconds) throw refused
                    delay(200)
                }
            }
        }
        assertFalse(realTime(MediaItem(clip.absolutePath)), "a file")
        val rtmp = "rtmp://127.0.0.1:${freePort()}/live/kite"
        sender(clip, "rtmp-realtime", listOf("-f", "flv", "-listen", "1", "-timeout", "15", rtmp)).use {
            assertTrue(realTime(MediaItem(rtmp), retry = true), "an rtmp feed")
        }
        val tcp = freePort()
        sender(clip, "tcp-realtime", listOf("-f", "mpegts", "tcp://127.0.0.1:$tcp?listen=1&listen_timeout=15000")).use {
            assertFalse(realTime(MediaItem("tcp://127.0.0.1:$tcp"), retry = true), "a tcp stream")
        }
        val udp = freePort()
        coroutineScope {
            val opening = async(Dispatchers.Default) { realTime(MediaItem("udp://127.0.0.1:$udp")) }
            delay(500)
            sender(clip, "udp-realtime", listOf("-f", "mpegts", "udp://127.0.0.1:$udp?pkt_size=1316")).use {
                assertTrue(opening.await(), "a udp stream")
            }
        }
    }

    /** A silent sender ends the playback once one read has waited the read timeout. */
    @Test
    fun aUdpSenderThatGoesSilentFailsThePlaybackWithinTheReadTimeout() = live { clip ->
        val port = freePort()
        val item = MediaItem("udp://127.0.0.1:$port")
        endsAfterSilence("udp", item, PlaybackStatus.Failed, URL_FALLBACK_READ_TIMEOUT + 5.seconds, startSender = {
            sender(clip, "udp-silent", listOf("-f", "mpegts", "udp://127.0.0.1:$port?pkt_size=1316"))
        })
    }

    /**
     * Over a TCP connection FFmpeg waits for the network a second time after the first read times
     * out, so a camera that keeps its connection and stops sending fails the playback after two
     * read timeouts: 6.3 seconds with a timeout of 3, and 20.2 with the fallback's 10. The command
     * line does the same, giving up on a frozen RTMP sender 6.2 seconds after it froze with
     * `-rw_timeout` at 3 seconds.
     */
    @Test
    fun anRtspCameraThatGoesSilentFailsThePlaybackWithinTwoReadTimeouts() = live { clip ->
        RtspRelay().use { relay ->
            sender(clip, "rtsp-silent", listOf("-f", "rtsp", "-rtsp_transport", "tcp", relay.url)).use { publisher ->
                check(relay.awaitAnnounced(15)) { "nothing was published: ${publisher.logText()}" }
                val item = MediaItem(relay.url, openOptions = mapOf("rtsp_transport" to "tcp"))
                endsAfterSilence("rtsp", item, PlaybackStatus.Failed, URL_FALLBACK_READ_TIMEOUT * 2 + 5.seconds, silence = relay::hold)
            }
        }
    }

    /** A sender that stops and keeps its connection, as the RTSP camera above. */
    @Test
    fun anRtmpSenderThatGoesSilentFailsThePlaybackWithinTwoReadTimeouts() = live { clip ->
        val url = "rtmp://127.0.0.1:${freePort()}/live/kite"
        sender(clip, "rtmp-silent", listOf("-f", "flv", "-listen", "1", "-timeout", "15", url)).use { publisher ->
            val within = URL_FALLBACK_READ_TIMEOUT * 2 + 5.seconds
            endsAfterSilence("rtmp", MediaItem(url), PlaybackStatus.Failed, within, retryOpen = true, silence = publisher::freeze)
        }
    }

    /** A sender that hangs up ends the playback at once, after the second of media the player holds. */
    @Test
    fun anRtmpSenderThatHangsUpEndsThePlayback() = live { clip ->
        val url = "rtmp://127.0.0.1:${freePort()}/live/kite"
        sender(clip, "rtmp-hangup", listOf("-f", "flv", "-listen", "1", "-timeout", "15", url)).use { publisher ->
            endsAfterSilence("rtmp hanging up", MediaItem(url), null, 5.seconds, retryOpen = true, silence = publisher::close)
        }
    }

    @Test
    fun aTcpSenderThatHangsUpEndsThePlayback() = live { clip ->
        val port = freePort()
        sender(clip, "tcp-hangup", listOf("-f", "mpegts", "tcp://127.0.0.1:$port?listen=1&listen_timeout=15000")).use { publisher ->
            endsAfterSilence("tcp hanging up", MediaItem("tcp://127.0.0.1:$port"), null, 5.seconds, retryOpen = true, silence = publisher::close)
        }
    }

    /**
     * Plays [item] for three seconds, then stops the sender, by closing the one [startSender]
     * started or through [silence], and checks that the playback stops being active within
     * [within], ending as [expected] when that is not null.
     */
    private suspend fun endsAfterSilence(
        name: String,
        item: MediaItem,
        expected: PlaybackStatus?,
        within: Duration,
        retryOpen: Boolean = false,
        startSender: (() -> FFmpegProcess)? = null,
        silence: () -> Unit = {},
    ) {
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), PacedOutput()), progressInterval = 50.milliseconds),
        )
        var sender: FFmpegProcess? = null
        try {
            player.attachRendererAndAwait(FlashRecorder())
            withTimeout(30.seconds) {
                coroutineOpen(player, item, retryOpen) {
                    if (startSender != null) {
                        delay(500)
                        sender = startSender()
                    }
                }
            }
            player.play()
            withTimeout(15.seconds) { while (player.state.value.status != PlaybackStatus.Playing) delay(20) }
            delay(3.seconds)
            val silent = TimeSource.Monotonic.markNow()
            sender?.close()
            silence()
            val took = withTimeoutOrNull(within + 10.seconds) {
                while (player.state.value.status.isActive) delay(50)
                silent.elapsedNow()
            }
            val state = player.state.value
            println("LivePlaybackTest: $name went silent and the player was ${state.status} after $took, error ${state.error}")
            assertTrue(took != null && took <= within, "$name: the player was still ${state.status} after ${took ?: (within + 10.seconds)}")
            if (expected != null) assertEquals(expected, state.status, "$name: error ${state.error}")
        } finally {
            player.closeAndAwait()
            sender?.close()
        }
    }

    /** The player dials [RtspRelay] as it would a camera, over [transport], which the item chooses. */
    private fun rtspPlaysInSync(transport: String) = live { clip ->
        RtspRelay().use { relay ->
            sender(clip, "rtsp-$transport", listOf("-f", "rtsp", "-rtsp_transport", "tcp", relay.url)).use { publisher ->
                check(relay.awaitAnnounced(15)) { "nothing was published: ${publisher.logText()}" }
                playsInSync("rtsp over $transport", MediaItem(relay.url, openOptions = mapOf("rtsp_transport" to transport)))
            }
        }
    }

    /**
     * Opens [item], plays six seconds of it and checks each beep against its flash. [startSender]
     * starts a sender that must not begin before the player listens; [retryOpen] dials a sender that
     * listens, again and again until it does.
     */
    private suspend fun playsInSync(
        name: String,
        item: MediaItem,
        retryOpen: Boolean = false,
        startSender: (() -> FFmpegProcess)? = null,
    ) {
        val output = PacedOutput()
        val renderer = FlashRecorder()
        val player = KitePlayer.create(
            PlayerConfig(backends = Backends(KiteFFmpegMediaBackend(), output), progressInterval = 50.milliseconds),
        )
        var sender: FFmpegProcess? = null
        try {
            player.attachRendererAndAwait(renderer)
            withTimeout(30.seconds) {
                coroutineOpen(player, item, retryOpen) {
                    if (startSender != null) {
                        delay(500)
                        sender = startSender()
                    }
                }
            }
            player.play()
            withTimeout(15.seconds) { while (player.state.value.status != PlaybackStatus.Playing) delay(20) }
            val settled = output.clock.nanos() + 1_000_000_000L
            delay(7.seconds)
            val first = syncOffsetsMillis(output.beeps.filter { it.atNanos < settled }, renderer.flashes)
            val offsets = syncOffsetsMillis(output.beeps.filter { it.atNanos >= settled }, renderer.flashes)
            val warnings = player.warningHistory().map { it.warning }
            val seen = "tracks ${player.state.value.tracks.all}, warnings $warnings"
            println("LivePlaybackTest: $name, sound behind the picture by ${offsets.map { it.toInt() }} ms, first second ${first.map { it.toInt() }}")
            assertTrue(offsets.size >= 4, "$name: only ${offsets.size} beeps with a flash: ${output.beeps.size} beeps, ${renderer.flashes.size} flashes; $seen")
            assertTrue(offsets.all { it in -40.0..60.0 }, "$name: sound behind the picture by $offsets ms, outside -40 to 60")
            val unused = warnings.filterIsInstance<PlaybackWarning.OptionsUnused>()
            assertTrue(unused.isEmpty(), "$name: options went unused: $unused")
        } finally {
            player.closeAndAwait()
            sender?.close()
        }
    }

    /** Opens [item] while [alongside] runs, dialling again for ten seconds when [retry] says so. */
    private suspend fun coroutineOpen(player: KitePlayer, item: MediaItem, retry: Boolean, alongside: suspend () -> Unit) {
        coroutineScope {
            val opening = async(Dispatchers.Default) {
                val started = TimeSource.Monotonic.markNow()
                while (true) {
                    try {
                        player.open(item)
                        break
                    } catch (refused: PlaybackException) {
                        if (!retry || started.elapsedNow() > 10.seconds) throw refused
                        delay(200)
                    }
                }
            }
            alongside()
            opening.await()
        }
    }

    /** Runs [test] with a fourteen second marker clip, or skips it when there is no `ffmpeg`. */
    private fun live(test: suspend (File) -> Unit) = runBlocking {
        if (ffmpegCli == null) return@runBlocking println("SKIP: no ffmpeg on PATH")
        withTimeout(90.seconds) { test(MarkerClip.make(14)) }
    }

    /** The command line sending [clip] in real time, unchanged, to [outputs]. */
    private fun sender(clip: File, name: String, outputs: List<String>, input: List<String> = emptyList()): FFmpegProcess =
        FFmpegProcess(
            listOf("-v", "warning", "-re") + input + listOf("-i", clip.absolutePath, "-c", "copy") + outputs,
            File(MarkerClip.dir, "sender-$name-${System.nanoTime()}.log"),
        )

    /**
     * The picture to [video] and the sound to [audio] as two RTP streams. A codec option belongs to
     * the output after it, so the second output copies too.
     */
    private fun rtpOutputs(video: Int, audio: Int): List<String> =
        listOf("-map", "0:v", "-f", "rtp", "rtp://127.0.0.1:$video") +
            listOf("-map", "0:a", "-c", "copy", "-f", "rtp", "rtp://127.0.0.1:$audio")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /**
     * The IPv4 address of an interface on which a datagram sent to [GROUP] comes back to this host,
     * or null when none does, as in a container whose loopback has no multicast.
     */
    private fun multicastLoopAddress(): String? {
        val group = InetAddress.getByName(GROUP)
        for (candidate in NetworkInterface.networkInterfaces().toList()) {
            if (!candidate.isUp || !candidate.supportsMulticast()) continue
            val address = candidate.inetAddresses.toList().firstOrNull { it is Inet4Address } ?: continue
            val loops = runCatching {
                MulticastSocket(0).use { socket ->
                    socket.networkInterface = candidate
                    socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
                    socket.joinGroup(InetSocketAddress(group, socket.localPort), candidate)
                    socket.soTimeout = 500
                    socket.send(DatagramPacket(byteArrayOf(1), 1, group, socket.localPort))
                    socket.receive(DatagramPacket(ByteArray(16), 16))
                    true
                }
            }.getOrDefault(false)
            if (loops) return address.hostAddress
        }
        return null
    }

    /** An even port with the three above it free too, because RTP sends its control packets one port up. */
    private fun evenPort(): Int = Random.nextInt(10_000, 30_000) * 2

    private companion object {
        /** A group in the organisation-local scope, which no router passes on. */
        const val GROUP = "239.255.42.42"
    }
}
