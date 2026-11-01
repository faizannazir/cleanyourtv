package com.faizannazir.cleanyourtv.tv.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The byte transport under [AdbConnection].
 *
 * It exists so the protocol can be tested deterministically over in-memory streams; production uses
 * [TcpAdbSocketFactory], which is the only implementation that opens a real socket.
 */
internal interface AdbSocket : AutoCloseable {
    fun input(): InputStream
    fun output(): OutputStream
    fun setReadTimeout(millis: Int)
    override fun close()
}

internal fun interface AdbSocketFactory {
    fun open(): AdbSocket
}

internal class TcpAdbSocketFactory(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int
) : AdbSocketFactory {

    override fun open(): AdbSocket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        return TcpAdbSocket(socket)
    }
}

private class TcpAdbSocket(private val socket: Socket) : AdbSocket {

    private val inputStream = BufferedInputStream(socket.getInputStream())
    private val outputStream = BufferedOutputStream(socket.getOutputStream())

    override fun input(): InputStream = inputStream

    override fun output(): OutputStream = outputStream

    override fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        socket.close()
    }
}
