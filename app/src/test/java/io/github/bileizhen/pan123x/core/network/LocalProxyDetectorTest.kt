package io.github.bileizhen.pan123x.core.network

import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalProxyDetectorTest {
    @Test fun httpDetectionUsesProtocolHandshakeAndCachesForFiveMinutes() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            var time = 0L
            val detector = LocalProxyDetector(listOf(server.localPort)) { time }
            val exchanged = executor.submit {
                repeat(2) { server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream()
                    val request = StringBuilder()
                    while (true) { val next = input.read(); if (next < 0) break; request.append(next.toChar()); if (request.endsWith("\r\n\r\n")) break }
                    if (request.isNotEmpty()) {
                        assertTrue(request.startsWith("CONNECT www.example.com:443"))
                        assertFalse(request.contains("Authorization"))
                        socket.getOutputStream().apply { write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); flush() }
                    }
                } }
            }
            try {
                val proxy = detector.detect()!!
                assertEquals(Proxy.Type.HTTP, proxy.type())
                exchanged.get(4, TimeUnit.SECONDS)
                assertEquals(proxy, detector.detect())
                time = 299_999; assertEquals(proxy, detector.detect())
                server.close(); time = 300_000; assertNull(detector.detect())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun socksDetectionHandlesSplitGreetingAndRejectsHttpFailure() {
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val exchanged = executor.submit {
                repeat(3) { index -> server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    if (index == 1) {
                        while (socket.getInputStream().read() != 10) { }
                        socket.getOutputStream().apply { write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray()); flush() }
                    } else if (index == 2) {
                        val input = socket.getInputStream()
                        assertEquals(5, input.read()); assertEquals(1, input.read()); assertEquals(0, input.read())
                        socket.getOutputStream().apply { write(5); flush(); write(0); flush() }
                    }
                } }
            }
            try {
                val route = LocalProxyDetector(listOf(server.localPort)).detect()!!
                assertEquals(Proxy.Type.SOCKS, route.type()); exchanged.get(4, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
