package com.faizannazir.cleanyourtv.tv.adb

import android.content.Context
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * The outcome of an install attempted through the Android package service.
 *
 * [Confirmed] is only ever returned after the commit printed `Success` *and* the package showed up
 * in `pm list packages`. Anything else is a failure the caller must not upgrade to success.
 */
sealed class InstallResult {
    data class Confirmed(val sessionId: Int, val detail: String) : InstallResult()
    data class Failed(val message: String) : InstallResult()
    data class Uncertain(val message: String) : InstallResult()
}

/**
 * A minimal ADB client for the TV ADB daemon.
 *
 * The connection is only marked connected after the peer has answered our `CNXN` with a `CNXN`
 * (possibly after signing an `AUTH` token and being authorised on screen). Every frame is validated
 * by [AdbMessage.readFrom]. Remote commands are never executed locally, and a stream that does not
 * end with the remote `CLSE` is reported as *uncertain*, never as success.
 *
 * [authenticator] and the timeouts are injectable so the transport can be exercised over a local
 * scripted socket in unit tests. The public constructor is the one the app uses.
 */
class AdbConnection internal constructor(
    private val socketFactory: AdbSocketFactory,
    private val authenticator: AdbAuthenticator,
    val host: String,
    val port: Int,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
) {
    constructor(context: Context, host: String = "127.0.0.1", port: Int = 5555) : this(
        TcpAdbSocketFactory(host, port, DEFAULT_CONNECT_TIMEOUT_MS),
        KeyManagerAuthenticator(AdbKeyManager(context)),
        host,
        port
    )

    private var socket: AdbSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var connected = false
    private var nextStreamId = 1

    /** Why the last [connect] or stream operation failed; never contains TV data. */
    var lastErrorMessage: String? = null
        private set

    /**
     * Opens the socket and completes the ADB `CNXN`/`AUTH` handshake.
     *
     * Returns `true` only once the peer has sent `CNXN`. On every failure the socket is closed, no
     * stream is left open, and [lastErrorMessage] says why.
     */
    @Synchronized
    fun connect(): Boolean {
        if (connected && socket != null) return true

        releaseSocket()
        lastErrorMessage = null

        val sock = try {
            socketFactory.open()
        } catch (e: Exception) {
            lastErrorMessage = "Could not open an ADB connection to $host:$port: ${describe(e)}"
            return false
        }

        return try {
            socket = sock
            input = sock.input()
            output = sock.output()
            handshake(sock)
            sock.setReadTimeout(readTimeoutMs)
            connected = true
            true
        } catch (e: Exception) {
            lastErrorMessage = "ADB handshake with $host:$port failed: ${describe(e)}"
            releaseSocket()
            false
        }
    }

    /**
     * Runs one command through the ADB `shell:` service.
     *
     * A result is only `Success:` when the remote stream ended with `CLSE`. An interrupted or
     * malformed response closes the connection and returns an `Uncertain ADB result` error, because
     * the command may or may not have run; callers must not retry it automatically.
     */
    @Synchronized
    fun executeShellCommand(command: String): String {
        if (!connected) {
            return "Error: Not connected to the TV ADB daemon."
        }

        val streamId = allocateStreamId()
        return try {
            socket?.setReadTimeout(readTimeoutMs)
            send(AdbMessage(AdbMessage.A_OPEN, streamId, 0, servicePayload("shell:$command")))
            val remoteId = awaitOpen(streamId)
            val text = StringBuilder()
            readUntilClose(streamId, remoteId, text)
            formatSuccess(text.toString())
        } catch (e: AdbStreamRefusedException) {
            "Error: The TV refused the command stream: ${describe(e)}"
        } catch (e: SocketTimeoutException) {
            failUncertain("no complete response arrived within $readTimeoutMs ms (${describe(e)})")
        } catch (e: EOFException) {
            failUncertain("the TV closed the ADB stream before the command finished (${describe(e)})")
        } catch (e: AdbProtocolException) {
            failUncertain("the TV sent a malformed ADB response (${describe(e)})")
        } catch (e: RuntimeException) {
            failUncertain("the ADB transport failed (${describe(e)})")
        } catch (e: IOException) {
            failUncertain("the ADB transport failed (${describe(e)})")
        }
    }

    /**
     * Installs [apkSize] bytes from [apkInputStream] with the package service's session protocol:
     * `install-create` -> stream the APK into `install-write` -> `install-commit` -> verify the
     * package is listed.
     *
     * The APK is written on an `exec:` stream, so no file is ever staged on the TV. Success is
     * reported only after the commit answered `Success` and [packageToVerify] is present.
     */
    @Synchronized
    fun installPackage(
        sessionName: String,
        apkSize: Long,
        packageToVerify: String,
        apkInputStream: InputStream
    ): InstallResult {
        if (!connected) return InstallResult.Failed("Not connected to the TV ADB daemon.")

        val create = executeShellCommand("pm install-create -r -S $apkSize")
        when (shellFailure(create)) {
            ShellFailure.UNCERTAIN -> return InstallResult.Uncertain(
                "Creating the install session was interrupted: $create"
            )
            ShellFailure.ERROR -> return InstallResult.Failed(
                "The TV refused to create an install session: $create"
            )
            null -> Unit
        }

        val sessionId = SESSION_ID.find(create)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return InstallResult.Failed("The TV did not return an install session id: $create")

        val writeOutput = try {
            writeSession(sessionId, sessionName, apkSize, apkInputStream)
        } catch (e: AdbStreamRefusedException) {
            abandonSession(sessionId)
            return InstallResult.Failed("The TV closed the APK stream before the transfer: ${describe(e)}")
        } catch (e: Exception) {
            lastErrorMessage = describe(e)
            releaseSocket()
            return InstallResult.Uncertain(
                "The APK transfer was interrupted, so the install may be incomplete: ${describe(e)}"
            )
        }

        if (!reportedSuccess(writeOutput)) {
            abandonSession(sessionId)
            return InstallResult.Failed(
                "The TV did not accept the APK bytes: ${writeOutput.trim().ifEmpty { "no output" }}"
            )
        }

        val commit = executeShellCommand("pm install-commit $sessionId")
        when (shellFailure(commit)) {
            ShellFailure.UNCERTAIN -> return InstallResult.Uncertain(
                "Committing the install session was interrupted: $commit"
            )
            ShellFailure.ERROR -> {
                abandonSession(sessionId)
                return InstallResult.Failed("The TV rejected the installation: $commit")
            }
            null -> Unit
        }
        if (!reportedSuccess(commit)) {
            abandonSession(sessionId)
            return InstallResult.Failed(
                "The TV did not confirm the installation: ${commit.trim().ifEmpty { "no output" }}"
            )
        }

        val listed = executeShellCommand("pm list packages $packageToVerify")
        when (shellFailure(listed)) {
            ShellFailure.UNCERTAIN -> return InstallResult.Uncertain(
                "Verifying the installed package was interrupted: $listed"
            )
            ShellFailure.ERROR -> return InstallResult.Uncertain(
                "The TV did not answer the package verification: $listed"
            )
            null -> Unit
        }
        if (!isPackageListed(listed, packageToVerify)) {
            return InstallResult.Failed(
                "The install reported success, but $packageToVerify is not listed as installed."
            )
        }

        return InstallResult.Confirmed(sessionId, commit.trim())
    }

    @Synchronized
    fun disconnect() {
        connected = false
        releaseSocket()
    }

    fun isConnected(): Boolean = connected

    private fun handshake(sock: AdbSocket) {
        send(
            AdbMessage(
                AdbMessage.A_CNXN,
                AdbMessage.A_VERSION,
                AdbMessage.MAX_PAYLOAD,
                "host::\u0000".toByteArray(Charsets.UTF_8)
            )
        )

        var signatureAttempts = 0
        var askedForKey = false

        repeat(MAX_HANDSHAKE_FRAMES) {
            val msg = readFrame()
            when (msg.command) {
                AdbMessage.A_CNXN -> return
                AdbMessage.A_AUTH -> when (msg.arg0) {
                    AdbMessage.AUTH_TYPE_TOKEN -> {
                        if (signatureAttempts >= MAX_SIGNATURE_ATTEMPTS) {
                            throw AdbProtocolException("the TV rejected the ADB key signature")
                        }
                        signatureAttempts++
                        val signature = authenticator.signToken(msg.data)
                        send(AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TYPE_SIGNATURE, 0, signature))
                    }
                    AdbMessage.AUTH_TYPE_RSA_PUBLIC -> {
                        if (askedForKey) {
                            throw AdbProtocolException("the TV did not authorise the ADB key in time")
                        }
                        askedForKey = true
                        send(
                            AdbMessage(
                                AdbMessage.A_AUTH,
                                AdbMessage.AUTH_TYPE_RSA_PUBLIC,
                                0,
                                authenticator.publicKeyPayload()
                            )
                        )
                        // The person has to accept the debugging prompt on the TV.
                        sock.setReadTimeout(authorizationTimeoutMs(readTimeoutMs))
                    }
                    else -> throw AdbProtocolException("unsupported ADB auth type ${msg.arg0}")
                }
                AdbMessage.A_STLS -> throw AdbProtocolException(
                    "the TV requested ADB over TLS, which this transport does not implement"
                )
                else -> throw AdbProtocolException(
                    "unexpected ADB frame 0x${msg.command.toHex()} during the handshake"
                )
            }
        }
        throw AdbProtocolException("the ADB handshake did not finish")
    }

    private fun writeSession(
        sessionId: Int,
        sessionName: String,
        apkSize: Long,
        apkInputStream: InputStream
    ): String {
        val streamId = allocateStreamId()
        socket?.setReadTimeout(installTimeoutMs(readTimeoutMs))
        send(
            AdbMessage(
                AdbMessage.A_OPEN,
                streamId,
                0,
                servicePayload("exec:pm install-write -S $apkSize $sessionId $sessionName -")
            )
        )
        val remoteId = awaitOpen(streamId)

        val collected = StringBuilder()
        val buffer = ByteArray(AdbMessage.MAX_PAYLOAD)
        while (true) {
            val read = apkInputStream.read(buffer)
            if (read < 0) break
            if (read == 0) throw IOException("the APK stream made no progress")
            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
            send(AdbMessage(AdbMessage.A_WRTE, streamId, remoteId, chunk))
            awaitWriteAck(streamId, remoteId, collected)
        }

        send(AdbMessage(AdbMessage.A_CLSE, streamId, remoteId, ByteArray(0)))
        readUntilClose(streamId, remoteId, collected)
        return collected.toString()
    }

    private fun awaitOpen(streamId: Int): Int {
        while (true) {
            val msg = readFrame()
            when (msg.command) {
                AdbMessage.A_OKAY -> {
                    if (msg.arg0 != streamId) {
                        throw AdbProtocolException("OKAY for stream ${msg.arg0}, expected $streamId")
                    }
                    return msg.arg1
                }
                AdbMessage.A_CLSE -> {
                    if (msg.arg1 == streamId) {
                        throw AdbStreamRefusedException("the remote closed stream $streamId before accepting it")
                    }
                    throw AdbProtocolException("CLSE for stream ${msg.arg1}, expected $streamId")
                }
                else -> throw AdbProtocolException(
                    "unexpected ADB frame 0x${msg.command.toHex()} while opening stream $streamId"
                )
            }
        }
    }

    private fun awaitWriteAck(streamId: Int, remoteId: Int, sink: StringBuilder) {
        while (true) {
            val msg = readFrame()
            when (msg.command) {
                AdbMessage.A_OKAY -> {
                    if (msg.arg0 != streamId) {
                        throw AdbProtocolException("OKAY for stream ${msg.arg0}, expected $streamId")
                    }
                    return
                }
                AdbMessage.A_WRTE -> {
                    if (msg.arg1 != streamId) {
                        throw AdbProtocolException("WRTE for stream ${msg.arg1}, expected $streamId")
                    }
                    appendChunk(sink, msg.data)
                    send(AdbMessage(AdbMessage.A_OKAY, streamId, remoteId, ByteArray(0)))
                }
                AdbMessage.A_CLSE -> {
                    if (msg.arg1 == streamId) {
                        throw AdbStreamRefusedException("the remote closed stream $streamId during the transfer")
                    }
                    throw AdbProtocolException("CLSE for stream ${msg.arg1}, expected $streamId")
                }
                else -> throw AdbProtocolException(
                    "unexpected ADB frame 0x${msg.command.toHex()} while writing stream $streamId"
                )
            }
        }
    }

    private fun readUntilClose(streamId: Int, remoteId: Int, sink: StringBuilder) {
        var received = sink.length
        while (true) {
            val msg = readFrame()
            when (msg.command) {
                AdbMessage.A_WRTE -> {
                    if (msg.arg1 != streamId) {
                        throw AdbProtocolException("WRTE for stream ${msg.arg1}, expected $streamId")
                    }
                    received += msg.data.size
                    if (received > MAX_OUTPUT_BYTES) {
                        throw AdbProtocolException("the command produced more than $MAX_OUTPUT_BYTES bytes")
                    }
                    appendChunk(sink, msg.data)
                    send(AdbMessage(AdbMessage.A_OKAY, streamId, remoteId, ByteArray(0)))
                }
                AdbMessage.A_CLSE -> {
                    if (msg.arg1 != streamId) {
                        throw AdbProtocolException("CLSE for stream ${msg.arg1}, expected $streamId")
                    }
                    return
                }
                AdbMessage.A_OKAY -> {
                    if (msg.arg0 != streamId) {
                        throw AdbProtocolException("OKAY for stream ${msg.arg0}, expected $streamId")
                    }
                }
                else -> throw AdbProtocolException(
                    "unexpected ADB frame 0x${msg.command.toHex()} while reading stream $streamId"
                )
            }
        }
    }

    private fun send(message: AdbMessage) {
        val stream = output ?: throw IOException("the ADB socket is not open")
        stream.write(message.toByteArray())
        stream.flush()
    }

    private fun readFrame(): AdbMessage {
        val stream = input ?: throw IOException("the ADB socket is not open")
        return AdbMessage.readFrom(stream)
    }

    private fun allocateStreamId(): Int {
        if (nextStreamId >= Int.MAX_VALUE - 1) nextStreamId = 1
        return nextStreamId++
    }

    private fun formatSuccess(raw: String): String {
        // No artificial "Success:" prefix: a command that answers "Error:" or "Failure" must stay
        // visible as a failure to the caller and to ShellOutput.isFailure.
        val text = raw.trim()
        return text.ifEmpty { EMPTY_OUTPUT_NOTICE }
    }

    private fun reportedSuccess(raw: String): Boolean =
        raw.lineSequence()
            .map { it.trim() }
            .any { it == "Success" || it.startsWith("Success:") || it.startsWith("Success ") }

    private fun failUncertain(detail: String): String {
        lastErrorMessage = detail
        // The stream state is unknown, so the socket cannot be reused safely.
        connected = false
        releaseSocket()
        return "Error: $UNCERTAIN_MARKER - $detail. The command may or may not have run; " +
                "it was not retried automatically."
    }

    private fun abandonSession(sessionId: Int) {
        if (connected) {
            runCatching { executeShellCommand("pm install-abandon $sessionId") }
        }
    }

    private fun releaseSocket() {
        connected = false
        val sock = socket
        socket = null
        input = null
        output = null
        try {
            sock?.close()
        } catch (_: IOException) {
            // Nothing useful can be done with a socket that is already broken.
        }
    }

    private fun appendChunk(sink: StringBuilder, bytes: ByteArray) {
        if (sink.length + bytes.size > MAX_OUTPUT_BYTES) {
            throw AdbProtocolException("the command produced more than $MAX_OUTPUT_BYTES bytes")
        }
        sink.append(String(bytes, Charsets.UTF_8))
    }

    private fun shellFailure(raw: String): ShellFailure? = when {
        raw.contains(UNCERTAIN_MARKER) -> ShellFailure.UNCERTAIN
        raw.startsWith("Error:") -> ShellFailure.ERROR
        else -> null
    }

    private fun isPackageListed(raw: String, packageName: String): Boolean =
        raw.lineSequence()
            .map { it.removePrefix("Success:").trim() }
            .any { it == "package:$packageName" }

    private enum class ShellFailure { ERROR, UNCERTAIN }

    private class AdbStreamRefusedException(message: String) : IOException(message)

    companion object {
        /** A shell command that succeeded but printed nothing. */
        const val EMPTY_OUTPUT_NOTICE = "Command transmitted to TV ADB daemon."

        /** Marks a response that ended without the remote CLSE; the command may or may not have run. */
        const val UNCERTAIN_MARKER = "Uncertain ADB result"

        internal const val DEFAULT_CONNECT_TIMEOUT_MS = 2_500
        internal const val DEFAULT_READ_TIMEOUT_MS = 10_000

        private const val MAX_HANDSHAKE_FRAMES = 8
        private const val MAX_SIGNATURE_ATTEMPTS = 3
        private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        private val SESSION_ID = Regex("\\[\\s*(\\d+)\\s*]")

        private fun servicePayload(service: String): ByteArray =
            (service + "\u0000").toByteArray(Charsets.UTF_8)

        private fun authorizationTimeoutMs(readTimeoutMs: Int): Int =
            maxOf(readTimeoutMs, 60_000)

        private fun installTimeoutMs(readTimeoutMs: Int): Int =
            maxOf(readTimeoutMs, 60_000)

        private fun describe(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
