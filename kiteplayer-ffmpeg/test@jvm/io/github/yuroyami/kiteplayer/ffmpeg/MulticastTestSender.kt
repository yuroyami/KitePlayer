package io.github.yuroyami.kiteplayer.ffmpeg

import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.StandardSocketOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * FFmpeg supplies and paces MPEG-TS; the JVM sends it through the interface the multicast probe
 * verified. A host FFmpeg executable can have different multicast access from the test JVM (#551).
 * The player still receives real UDP multicast through KiteFFmpeg's own socket and demuxer.
 */
internal class MulticastTestSender private constructor(
    private val socket: MulticastSocket,
    private val process: Process,
    private val destination: InetSocketAddress,
    private val log: File,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val terminatingProducer = AtomicBoolean(false)
    private val sentBytes = AtomicLong()
    private val pumpFailure = AtomicReference<Throwable?>(null)
    private val endpoint = "${socket.localSocketAddress} -> $destination"
    private val pump = thread(start = false, isDaemon = true, name = "multicast-test-sender") {
        try {
            process.inputStream.use { input ->
                while (!closed.get()) {
                    val bytes = input.readNBytes(7 * 188)
                    if (bytes.isEmpty() || closed.get()) break
                    check(bytes.size % 188 == 0 && bytes.indices.step(188).all { bytes[it] == 0x47.toByte() }) {
                        "the multicast producer wrote an incomplete or unaligned MPEG-TS datagram"
                    }
                    socket.send(DatagramPacket(bytes, bytes.size, destination))
                    sentBytes.addAndGet(bytes.size.toLong())
                }
            }
            if (!closed.get()) check(sentBytes.get() > 0) { "the multicast producer wrote no media" }
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                if (!terminatingProducer.get()) check(process.exitValue() == 0) {
                    "the multicast producer exited with ${process.exitValue()}"
                }
            } else {
                check(closed.get()) { "the multicast producer did not finish after closing its output" }
            }
        } catch (failure: Throwable) {
            val shutdownIo = closed.get() && (
                failure is SocketException && socket.isClosed ||
                    failure is IOException && terminatingProducer.get()
                )
            if (!shutdownIo) pumpFailure.compareAndSet(null, failure)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var cleanupFailure: Throwable? = null
        fun clean(action: () -> Unit) {
            try {
                action()
            } catch (caught: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = caught
                else if (previous !== caught) previous.addSuppressed(caught)
            }
        }
        clean { socket.close() }
        clean { terminate(process, terminatingProducer) }
        clean {
            pump.join(5_000)
            check(!pump.isAlive) { "the multicast sender worker did not stop" }
        }
        val failure = pumpFailure.get() ?: cleanupFailure
        if (failure != null) {
            val cleanup = cleanupFailure
            if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
            throw IllegalStateException("multicast sender $endpoint failed: ${logText()}", failure)
        }
        check(sentBytes.get() > 0) { "multicast sender $endpoint sent no media: ${logText()}" }
    }

    private fun logText(): String = runCatching { log.readText().takeLast(2_000) }.getOrDefault("")

    companion object {
        fun start(clip: File, local: String, group: String, port: Int, log: File): MulticastTestSender {
            val socket = MulticastSocket(null)
            var process: Process? = null
            try {
                val address = InetAddress.getByName(local)
                val multicast = InetAddress.getByName(group)
                require(multicast.isMulticastAddress)
                val network = checkNotNull(NetworkInterface.getByInetAddress(address))
                socket.bind(InetSocketAddress(address, 0))
                socket.networkInterface = network
                socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true)
                socket.timeToLive = 1
                val producer = ProcessBuilder(
                    checkNotNull(ffmpegCli), "-nostdin", "-v", "warning", "-re", "-i", clip.absolutePath,
                    "-c", "copy", "-f", "mpegts", "pipe:1",
                ).redirectError(log).start()
                process = producer
                val sender = MulticastTestSender(socket, producer, InetSocketAddress(multicast, port), log)
                println("LivePlaybackTest: multicast sender ${sender.endpoint}; producer log ${log.absolutePath}")
                sender.pump.start()
                return sender
            } catch (failure: Throwable) {
                try {
                    socket.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                try {
                    process?.let { terminate(it) }
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun terminate(process: Process, terminating: AtomicBoolean? = null) {
            if (process.isAlive) {
                terminating?.set(true)
                process.destroy()
            } else {
                check(process.exitValue() == 0) { "the multicast producer exited with ${process.exitValue()}" }
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(2, TimeUnit.SECONDS)) { "the multicast producer did not stop" }
            }
        }
    }
}
