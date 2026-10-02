// SPDX-License-Identifier: GPL-3.0-only
// Local HTTP CONNECT / SOCKS5 detection and five-minute cache adapted from LeiFetch Proxy.kt.
package io.github.bileizhen.pan123x.core.network

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

class LocalProxyDetector(
    private val ports: List<Int> = listOf(7890, 7897, 10809, 10808, 1080, 1081, 8118, 8888, 20171, 8080),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var checkedAt: Long? = null
    private var cached: Proxy? = null
    @Synchronized fun detect(): Proxy? {
        val now = clock()
        if (checkedAt?.let { now - it in 0 until 300_000 } == true) return cached
        cached = ports.firstNotNullOfOrNull { port ->
            if (!probe(port) { true }) null
            else if (speaksHttp(port)) endpoint(port, Proxy.Type.HTTP)
            else if (speaksSocks(port)) endpoint(port, Proxy.Type.SOCKS)
            else null
        }
        checkedAt = now
        return cached
    }
    @Synchronized fun invalidate() { checkedAt = null; cached = null }
    private fun endpoint(port: Int, type: Proxy.Type) = Proxy(type, InetSocketAddress.createUnresolved("127.0.0.1", port))
    private fun speaksHttp(port: Int): Boolean = probe(port) { socket ->
        socket.getOutputStream().apply {
            write("CONNECT www.example.com:443 HTTP/1.1\r\nHost: www.example.com:443\r\n\r\n".toByteArray(Charsets.US_ASCII)); flush()
        }
        val input = socket.getInputStream()
        val status = StringBuilder()
        while (status.length < 256) {
            val next = input.read()
            if (next < 0 || next == 10) break
            if (next != 13) status.append(next.toChar())
        }
        status.startsWith("HTTP/1.") && status.toString().split(' ').getOrNull(1)?.matches(Regex("2[0-9]{2}")) == true
    }
    private fun speaksSocks(port: Int): Boolean = probe(port) { socket ->
        socket.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
        val input = socket.getInputStream()
        input.read() == 5 && input.read() == 0
    }
    private fun probe(port: Int, exchange: (Socket) -> Boolean): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 150)
            socket.soTimeout = 600
            exchange(socket)
        }
    } catch (_: IOException) { false }
}
