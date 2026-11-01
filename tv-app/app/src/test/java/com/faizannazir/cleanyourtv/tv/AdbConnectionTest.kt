package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.adb.AdbAuthenticator
import com.faizannazir.cleanyourtv.tv.adb.AdbConnection
import com.faizannazir.cleanyourtv.tv.adb.AdbMessage
import com.faizannazir.cleanyourtv.tv.adb.AdbSocket
import com.faizannazir.cleanyourtv.tv.adb.AdbSocketFactory
import com.faizannazir.cleanyourtv.tv.adb.InstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives the real [AdbConnection] and [AdbMessage] over a scripted in-memory byte transport.
 *
 * The production transport is a TCP socket; the protocol above it is identical here, so these tests
 * exercise the actual handshake, stream correlation, validation and install-session state machine
 * without a TV, an emulator or a network.
 */
class AdbConnectionTest {

    // ---------------------------------------------------------------- handshake

    @Test
    fun connectSucceedsOnlyAfterCnxn() {
        val (connection, socket) = connection(incoming = scripted(cnxn()))

        assertTrue(connection.lastErrorMessage, connection.connect())
        assertTrue(connection.isConnected())
        assertNull(connection.lastErrorMessage)
        assertFalse(socket.closed)

        val sent = frames(socket.outgoing.toByteArray())
        assertEquals(AdbMessage.A_CNXN, sent.first().command)
        assertEquals(AdbMessage.A_VERSION, sent.first().arg0)
        assertEquals(AdbMessage.MAX_PAYLOAD, sent.first().arg1)

        connection.disconnect()
        assertFalse(connection.isConnected())
        assertTrue(socket.closed)
    }

    @Test
    fun signatureTokenIsAnsweredAndCnxnCompletesTheConnection() {
        val authenticator = FakeAuthenticator()
        val (connection, socket) = connection(
            incoming = scripted(authToken(), cnxn()),
            authenticator = authenticator
        )

        assertTrue(connection.lastErrorMessage, connection.connect())
        assertEquals(1, authenticator.signatureCalls)

        val sent = frames(socket.outgoing.toByteArray())
        assertEquals(AdbMessage.A_CNXN, sent[0].command)
        assertEquals(AdbMessage.A_AUTH, sent[1].command)
        assertEquals(AdbMessage.AUTH_TYPE_SIGNATURE, sent[1].arg0)
        assertTrue(sent[1].data.contentEquals(authenticator.signature))
    }

    @Test
    fun publicKeyIsSentWhenTheTvAsksForAuthorization() {
        val authenticator = FakeAuthenticator()
        val (connection, socket) = connection(
            incoming = scripted(
                authToken(),
                AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TYPE_RSA_PUBLIC, 0, ByteArray(20) { 2 }),
                cnxn()
            ),
            authenticator = authenticator
        )

        assertTrue(connection.connect())

        val sent = frames(socket.outgoing.toByteArray())
        assertEquals(3, sent.size)
        assertEquals(AdbMessage.AUTH_TYPE_RSA_PUBLIC, sent[2].arg0)
        assertTrue(sent[2].data.contentEquals(authenticator.publicKey))
    }

    @Test
    fun rejectedSignatureIsNotAConnection() {
        val (connection, socket) = connection(
            incoming = scripted(authToken(), authToken(), authToken(), authToken())
        )

        assertFalse(connection.connect())
        assertFalse(connection.isConnected())
        assertNotNull(connection.lastErrorMessage)
        assertTrue(connection.lastErrorMessage!!.contains("rejected"))
        assertTrue(socket.closed)
    }

    @Test
    fun missingCnxnIsAFailedConnection() {
        val (connection, socket) = connection(incoming = ByteArray(0))

        assertFalse(connection.connect())
        assertFalse(connection.isConnected())
        assertNotNull(connection.lastErrorMessage)
        assertTrue(socket.closed)
    }

    @Test
    fun invalidMagicIsRejectedAndTheSocketIsClosed() {
        val (connection, socket) = connection(
            incoming = rawFrame(
                command = AdbMessage.A_CNXN,
                arg0 = AdbMessage.A_VERSION,
                arg1 = AdbMessage.MAX_PAYLOAD,
                data = ByteArray(0),
                magic = 0
            )
        )

        assertFalse(connection.connect())
        assertTrue(connection.lastErrorMessage!!.contains("magic"))
        assertTrue(socket.closed)
    }

    @Test
    fun invalidChecksumIsRejected() {
        val (connection, _) = connection(
            incoming = rawFrame(
                command = AdbMessage.A_CNXN,
                arg0 = AdbMessage.A_VERSION,
                arg1 = AdbMessage.MAX_PAYLOAD,
                data = ByteArray(0),
                checksum = 0xDEAD
            )
        )

        assertFalse(connection.connect())
        assertTrue(connection.lastErrorMessage!!.contains("checksum"))
    }

    @Test
    fun unknownCommandIsRejected() {
        val (connection, _) = connection(
            incoming = rawFrame(
                command = 0x12345678,
                arg0 = 0,
                arg1 = 0,
                data = ByteArray(0),
                magic = 0x12345678.inv()
            )
        )

        assertFalse(connection.connect())
        assertTrue(connection.lastErrorMessage!!.contains("command"))
    }

    @Test
    fun oversizedPayloadIsRejectedWithoutAllocatingIt() {
        val (connection, _) = connection(
            incoming = rawFrame(
                command = AdbMessage.A_CNXN,
                arg0 = AdbMessage.A_VERSION,
                arg1 = AdbMessage.MAX_PAYLOAD,
                data = ByteArray(0),
                declaredLength = AdbMessage.MAX_PAYLOAD + 1
            )
        )

        assertFalse(connection.connect())
        assertTrue(connection.lastErrorMessage!!.contains("4096"))
    }

    @Test
    fun socketFailureIsNotAConnection() {
        val connection = AdbConnection(
            AdbSocketFactory { throw IOException("scripted socket failure") },
            FakeAuthenticator(),
            "127.0.0.1",
            5555,
            1_500
        )

        assertFalse(connection.connect())
        assertFalse(connection.isConnected())
        assertTrue(connection.lastErrorMessage!!.contains("Could not open"))
    }

    // ---------------------------------------------------------------- shell

    @Test
    fun shellCommandReturnsOutputOnlyAfterClose() {
        val (connection, socket) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "hi\n"),
                close(1)
            )
        )
        assertTrue(connection.connect())

        assertEquals("hi", connection.executeShellCommand("echo hi"))
        assertTrue(connection.isConnected())

        val sent = frames(socket.outgoing.toByteArray())
        assertEquals(AdbMessage.A_OPEN, sent[1].command)
        assertEquals("shell:echo hi", serviceName(sent[1]))
        assertEquals(AdbMessage.A_OKAY, sent[2].command)
        assertEquals(1, sent[2].arg0)
    }

    @Test
    fun emptyShellOutputUsesTheEmptyNotice() {
        val (connection, _) = connection(incoming = scripted(cnxn(), okay(1), close(1)))
        assertTrue(connection.connect())

        assertEquals(
            AdbConnection.EMPTY_OUTPUT_NOTICE,
            connection.executeShellCommand("pm list packages -d")
        )
    }

    @Test
    fun refusedStreamIsAnErrorButKeepsTheConnection() {
        val (connection, _) = connection(incoming = scripted(cnxn(), close(1)))
        assertTrue(connection.connect())

        val result = connection.executeShellCommand("pm disable-user --user 0 com.example")

        assertTrue(result.startsWith("Error:"))
        assertFalse(result.contains(AdbConnection.UNCERTAIN_MARKER))
        assertTrue(connection.isConnected())
    }

    @Test
    fun interruptedShellResponseIsUncertainAndClosesTheConnection() {
        val (connection, socket) = connection(
            incoming = scripted(cnxn(), okay(1), write(1, "half"))
        )
        assertTrue(connection.connect())

        val result = connection.executeShellCommand("pm disable-user --user 0 com.example")

        assertTrue(result.contains(AdbConnection.UNCERTAIN_MARKER))
        assertFalse(result.startsWith("Success:"))
        assertFalse(connection.isConnected())
        assertTrue(socket.closed)
    }

    @Test
    fun malformedFrameMidResponseIsUncertain() {
        val (connection, socket) = connection(
            incoming = scripted(cnxn(), okay(1)) +
                    rawFrame(
                        command = AdbMessage.A_WRTE,
                        arg0 = 0,
                        arg1 = 1,
                        data = "x".toByteArray(),
                        magic = 0
                    )
        )
        assertTrue(connection.connect())

        val result = connection.executeShellCommand("pm disable-user --user 0 com.example")

        assertTrue(result.contains(AdbConnection.UNCERTAIN_MARKER))
        assertFalse(connection.isConnected())
        assertTrue(socket.closed)
    }

    @Test
    fun silentTvTimesOutAsUncertain() {
        val (connection, socket) = connection(
            incoming = scripted(cnxn(), okay(1)),
            timeoutWhenExhausted = true
        )
        assertTrue(connection.connect())

        val result = connection.executeShellCommand("pm disable-user --user 0 com.example")

        assertTrue(result.contains(AdbConnection.UNCERTAIN_MARKER))
        assertFalse(connection.isConnected())
        assertTrue(socket.closed)
    }

    @Test
    fun commandWithoutAConnectionNeverRunsLocally() {
        val (connection, _) = connection(incoming = scripted(cnxn()))

        val result = connection.executeShellCommand("echo should-not-run")

        assertTrue(result.startsWith("Error:"))
        assertFalse(result.contains("Local Output"))
        assertFalse(result.contains("Executed locally"))
    }

    // ---------------------------------------------------------------- install

    @Test
    fun installIsConfirmedOnlyAfterCreateWriteCommitAndPackageCheck() {
        val apk = ByteArray(5_000) { (it % 251).toByte() }
        val (connection, socket) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Success: created install session [42]\n"),
                close(1),
                okay(2),
                okay(2),
                okay(2),
                write(2, "Success: streamed 5000 bytes\n"),
                close(2),
                okay(3),
                write(3, "Success\n"),
                close(3),
                okay(4),
                write(4, "package:com.gathin.tvhome\n"),
                close(4)
            )
        )
        assertTrue(connection.connect())

        val result = connection.installPackage(
            sessionName = "base.apk",
            apkSize = 5_000,
            packageToVerify = "com.gathin.tvhome",
            apkInputStream = ByteArrayInputStream(apk)
        )

        assertTrue("expected Confirmed, got $result", result is InstallResult.Confirmed)
        assertEquals(42, (result as InstallResult.Confirmed).sessionId)

        val sent = frames(socket.outgoing.toByteArray())
        val exec = sent.first { it.command == AdbMessage.A_OPEN && serviceName(it).startsWith("exec:") }
        assertEquals(
            "exec:pm install-write -S 5000 42 base.apk -",
            serviceName(exec)
        )
        val chunks = sent.filter { it.command == AdbMessage.A_WRTE && it.arg0 == 2 }
        assertEquals(5_000, chunks.sumOf { it.data.size })
    }

    @Test
    fun refusedSessionCreationIsAFailure() {
        val (connection, _) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Error: failed to create install session\n"),
                close(1)
            )
        )
        assertTrue(connection.connect())

        val result = install(connection, apkSize = 100)

        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).message.contains("refused"))
    }

    @Test
    fun rejectedApkBytesAreAFailureAndAbandonTheSession() {
        val (connection, socket) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Success: created install session [7]\n"),
                close(1),
                okay(2),
                okay(2),
                write(2, "Failure [INSTALL_FAILED_INVALID_APK]\n"),
                close(2),
                okay(3),
                write(3, "Success\n"),
                close(3)
            )
        )
        assertTrue(connection.connect())

        val result = install(connection, apkSize = 10)

        assertTrue(result is InstallResult.Failed)
        val sent = frames(socket.outgoing.toByteArray())
        assertTrue(sent.any { it.command == AdbMessage.A_OPEN && serviceName(it) == "shell:pm install-abandon 7" })
    }

    @Test
    fun refusedCommitIsAFailure() {
        val (connection, _) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Success: created install session [9]\n"),
                close(1),
                okay(2),
                okay(2),
                write(2, "Success: streamed 10 bytes\n"),
                close(2),
                okay(3),
                write(3, "Error: INSTALL_FAILED_OLDER_SDK\n"),
                close(3),
                okay(4),
                write(4, "Success\n"),
                close(4)
            )
        )
        assertTrue(connection.connect())

        val result = install(connection, apkSize = 10)

        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).message.contains("rejected"))
    }

    @Test
    fun installThatDoesNotListThePackageIsNotSuccess() {
        val (connection, _) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Success: created install session [11]\n"),
                close(1),
                okay(2),
                okay(2),
                write(2, "Success: streamed 10 bytes\n"),
                close(2),
                okay(3),
                write(3, "Success\n"),
                close(3),
                okay(4),
                close(4)
            )
        )
        assertTrue(connection.connect())

        val result = install(connection, apkSize = 10)

        assertTrue(result is InstallResult.Failed)
        assertTrue((result as InstallResult.Failed).message.contains("not listed"))
    }

    @Test
    fun interruptedTransferIsUncertainNotSuccess() {
        val (connection, socket) = connection(
            incoming = scripted(
                cnxn(),
                okay(1),
                write(1, "Success: created install session [12]\n"),
                close(1),
                okay(2)
            )
        )
        assertTrue(connection.connect())

        val result = install(connection, apkSize = 10)

        assertTrue("expected Uncertain, got $result", result is InstallResult.Uncertain)
        assertFalse(connection.isConnected())
        assertTrue(socket.closed)
    }

    // ---------------------------------------------------------------- helpers

    private fun install(connection: AdbConnection, apkSize: Long): InstallResult =
        connection.installPackage(
            sessionName = "base.apk",
            apkSize = apkSize,
            packageToVerify = "com.gathin.tvhome",
            apkInputStream = ByteArrayInputStream(ByteArray(apkSize.toInt()) { 1 })
        )

    private fun connection(
        incoming: ByteArray,
        authenticator: AdbAuthenticator = FakeAuthenticator(),
        timeoutWhenExhausted: Boolean = false,
        readTimeoutMs: Int = 1_500
    ): Pair<AdbConnection, FakeSocket> {
        val socket = FakeSocket(ScriptedInput(incoming, timeoutWhenExhausted))
        val connection = AdbConnection(
            AdbSocketFactory { socket },
            authenticator,
            "127.0.0.1",
            5555,
            readTimeoutMs
        )
        return connection to socket
    }

    private fun scripted(vararg messages: AdbMessage): ByteArray = messages
        .map { it.toByteArray() }
        .reduceOrNull { acc, bytes -> acc + bytes }
        ?: ByteArray(0)

    private fun frames(bytes: ByteArray): List<AdbMessage> {
        val input = ByteArrayInputStream(bytes)
        val parsed = mutableListOf<AdbMessage>()
        while (input.available() > 0) {
            parsed += AdbMessage.readFrom(input)
        }
        return parsed
    }

    private fun cnxn(): AdbMessage =
        AdbMessage(
            AdbMessage.A_CNXN,
            AdbMessage.A_VERSION,
            AdbMessage.MAX_PAYLOAD,
            "device::fake;\u0000".toByteArray(Charsets.UTF_8)
        )

    private fun authToken(): AdbMessage =
        AdbMessage(AdbMessage.A_AUTH, AdbMessage.AUTH_TYPE_TOKEN, 0, ByteArray(20) { 1 })

    private fun okay(clientStreamId: Int): AdbMessage =
        AdbMessage(AdbMessage.A_OKAY, clientStreamId, 0, ByteArray(0))

    private fun write(clientStreamId: Int, text: String): AdbMessage =
        AdbMessage(AdbMessage.A_WRTE, 0, clientStreamId, text.toByteArray(Charsets.UTF_8))

    private fun close(clientStreamId: Int): AdbMessage =
        AdbMessage(AdbMessage.A_CLSE, 0, clientStreamId, ByteArray(0))

    private fun serviceName(message: AdbMessage): String =
        String(message.data, Charsets.UTF_8).trimEnd('\u0000')

    private fun rawFrame(
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray,
        magic: Int = command.inv(),
        checksum: Int = AdbMessage.generateChecksum(data),
        declaredLength: Int = data.size
    ): ByteArray {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_SIZE + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(declaredLength)
        buffer.putInt(checksum)
        buffer.putInt(magic)
        buffer.put(data)
        return buffer.array()
    }

    private class FakeSocket(
        private val inputStream: InputStream,
        val outgoing: ByteArrayOutputStream = ByteArrayOutputStream()
    ) : AdbSocket {
        var closed = false
        override fun input(): InputStream = inputStream
        override fun output(): OutputStream = outgoing
        override fun setReadTimeout(millis: Int) = Unit
        override fun close() {
            closed = true
        }
    }

    private class ScriptedInput(
        private val bytes: ByteArray,
        private val timeoutWhenExhausted: Boolean
    ) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index < bytes.size) return bytes[index++].toInt() and 0xFF
            if (timeoutWhenExhausted) throw SocketTimeoutException("scripted timeout")
            return -1
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (index >= bytes.size) {
                if (timeoutWhenExhausted) throw SocketTimeoutException("scripted timeout")
                return -1
            }
            val count = minOf(length, bytes.size - index)
            System.arraycopy(bytes, index, buffer, offset, count)
            index += count
            return count
        }
    }

    private class FakeAuthenticator : AdbAuthenticator {
        val signature = ByteArray(32) { 9 }
        val publicKey = "fake-public-key\u0000".toByteArray(Charsets.UTF_8)
        var signatureCalls = 0

        override fun signToken(token: ByteArray): ByteArray {
            signatureCalls++
            return signature
        }

        override fun publicKeyPayload(): ByteArray = publicKey
    }
}
