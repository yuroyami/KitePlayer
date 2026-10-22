package io.github.yuroyami.kiteplayer.ffmpeg

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** UDP sockets belong to the receiver's lifetime, including time outside the delivery set. */
class RtspRelayOwnershipTest {
    @Test
    fun closingTheRelayReleasesItsPausedUdpReceiver() {
        RtspRelay().use { relay ->
            Client(relay.url).use { client ->
                val ports = serverPorts(client.request("SETUP", udp = true))
                assertPortsBound(ports)
                client.request("PLAY")
                client.request("PAUSE")
                relay.close()
                assertPortsReleased(ports)
                relay.close()
                assertPortsReleased(ports)
            }
        }
    }

    @Test
    fun anAbruptDisconnectReleasesUdpBeforeTheRelayIsClosed() {
        val released = ReleaseObserver()
        RtspRelay(onReceiverReleased = released::completed).use { relay ->
            Client(relay.url).use { client ->
                val ports = serverPorts(client.request("SETUP", udp = true))
                assertPortsBound(ports)
                client.request("PLAY")
                client.request("PAUSE")
                client.abort()
                released.awaitOnce()
                assertPortsReleased(ports)
                relay.close()
                assertEquals(1, released.count.get(), "relay close released the receiver twice")
            }
        }
    }

    @Test
    fun aFailedSetupReplyReleasesTheUdpSocketsItAllocated() {
        val released = ReleaseObserver()
        val gate = ReplyGate(fail = true) { "server_port=" in it }
        RtspRelay(onReceiverReleased = released::completed, wrapOutput = gate::wrap).use { relay ->
            Client(relay.url).use { client ->
                PendingRequest(client, gate) { client.request("SETUP", udp = true) }.use { pending ->
                    val ports = serverPorts(gate.awaitEntered())
                    assertPortsBound(ports)
                    gate.proceed.countDown()
                    pending.assertIoFailure()
                    released.awaitOnce()
                    assertPortsReleased(ports)
                    relay.close()
                    assertEquals(1, released.count.get())
                }
            }
        }
    }

    @Test
    fun relayCloseReleasesPausedUdpEvenWhileItsServingThreadCannotFinish() {
        val released = ReleaseObserver()
        val gate = ReplyGate { "\r\nCSeq: 4\r\n" in it }
        RtspRelay(onReceiverReleased = released::completed, wrapOutput = gate::wrap).use { relay ->
            Client(relay.url).use { client ->
                val ports = serverPorts(client.request("SETUP", udp = true))
                assertPortsBound(ports)
                client.request("PLAY")
                client.request("PAUSE")
                PendingRequest(client, gate) { client.request("OPTIONS") }.use { pending ->
                    gate.awaitEntered()
                    // The serving thread remains inside its reply wrapper. close must release the
                    // paused receiver itself; its finally cannot do that on the relay's behalf.
                    relay.close()
                    assertEquals(1L, gate.proceed.count, "the reply was not held across close")
                    assertPortsReleased(ports)
                    assertEquals(1, released.count.get(), "close returned before releasing its receiver")
                    gate.proceed.countDown()
                    pending.assertIoFailure()
                    relay.close()
                    assertEquals(1, released.count.get())
                }
            }
        }
    }

    private class ReleaseObserver {
        val count = AtomicInteger()
        private val latch = CountDownLatch(1)

        fun completed() {
            count.incrementAndGet()
            latch.countDown()
        }

        fun awaitOnce() {
            assertTrue(latch.await(3, TimeUnit.SECONDS), "the receiver never completed release")
            assertEquals(1, count.get(), "the receiver completed release more than once")
        }
    }

    /** Stops one authored reply before its real write, or fails that write after ownership exists. */
    private class ReplyGate(private val fail: Boolean = false, private val matches: (String) -> Boolean) {
        private val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private var message = ""

        fun wrap(delegate: OutputStream): OutputStream = object : OutputStream() {
            override fun write(value: Int) = delegate.write(value)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val response = bytes.decodeToString(offset, offset + length)
                if (matches(response)) {
                    message = response
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS)) { "the test did not release the reply" }
                    if (fail) throw IOException("the test refused the allocated UDP SETUP reply")
                }
                delegate.write(bytes, offset, length)
            }

            override fun flush() = delegate.flush()
        }

        fun awaitEntered(): String {
            assertTrue(entered.await(3, TimeUnit.SECONDS), "the reply never reached the test gate")
            return message
        }
    }

    private class PendingRequest(
        private val client: Client,
        private val gate: ReplyGate,
        request: () -> String,
    ) : AutoCloseable {
        private val task = FutureTask { request() }
        private val worker = thread(isDaemon = true, name = "rtsp-ownership-request") { task.run() }

        fun assertIoFailure() {
            val failure = assertFailsWith<ExecutionException> { task.get(3, TimeUnit.SECONDS) }
            assertTrue(
                failure.cause is IOException && failure.cause !is SocketTimeoutException,
                "the request must fail from connection closure, not its timeout: $failure",
            )
        }

        override fun close() {
            gate.proceed.countDown()
            client.close()
            worker.join(3_000)
            assertTrue(!worker.isAlive, "the bounded request worker did not stop")
        }
    }

    private class Client(private val url: String) : AutoCloseable {
        private val address = URI(url)
        private val socket = Socket().apply {
            connect(InetSocketAddress(address.host, address.port), 3_000)
            soTimeout = 3_000
        }
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        private val output = socket.getOutputStream()
        private var sequence = 0

        fun request(method: String, udp: Boolean = false): String {
            val cseq = ++sequence
            val target = if (udp) "$url/streamid=0" else url
            output.write(buildString {
                append("$method $target RTSP/1.0\r\nCSeq: $cseq\r\n")
                if (udp) append("Transport: RTP/AVP/UDP;unicast;client_port=10000-10001\r\n")
                append("\r\n")
            }.encodeToByteArray())
            output.flush()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) throw EOFException("$method ended before its reply")
                check(head.length < 16_384) { "reply exceeded the test bound" }
                head.append(next.toChar())
            }
            val result = head.toString()
            assertTrue(result.startsWith("RTSP/1.0 200 OK\r\n"), result)
            assertTrue("\r\nCSeq: $cseq\r\n" in result, result)
            return result
        }

        fun abort() {
            socket.setSoLinger(true, 0)
            socket.close()
        }

        override fun close() = socket.close()
    }

    private companion object {
        fun serverPorts(response: String): List<Int> {
            val match = checkNotNull(Regex("server_port=(\\d+)-(\\d+)").find(response)) { response }
            return listOf(match.groupValues[1].toInt(), match.groupValues[2].toInt()).also {
                assertEquals(2, it.toSet().size, "RTP and RTCP must own separate ports")
            }
        }

        fun assertPortsBound(ports: List<Int>) {
            ports.forEach { port ->
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = false
                    assertFailsWith<BindException>("server port $port was not owned before close") {
                        socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                    }
                }
            }
        }

        fun assertPortsReleased(ports: List<Int>) {
            ports.forEach { port ->
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
                    assertEquals(port, socket.localPort)
                }
            }
        }
    }
}
