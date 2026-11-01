package com.faizannazir.cleanyourtv.tv

import com.faizannazir.cleanyourtv.tv.adb.AdbMessage
import com.faizannazir.cleanyourtv.tv.adb.AdbProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbMessageTest {

    @Test
    fun testChecksumCalculation() {
        val data = "CNXN".toByteArray(Charsets.UTF_8)
        val checksum = AdbMessage.generateChecksum(data)
        assertEquals('C'.code + 'N'.code + 'X'.code + 'N'.code, checksum)
    }

    @Test
    fun testChecksumTreatsBytesAsUnsigned() {
        val data = byteArrayOf(0xFF.toByte(), 0x01, 0x80.toByte())
        assertEquals(0xFF + 0x01 + 0x80, AdbMessage.generateChecksum(data))
    }

    @Test
    fun testHeaderSerializationLength() {
        val data = "host::".toByteArray(Charsets.UTF_8)
        val msg = AdbMessage(AdbMessage.A_CNXN, 0x01000000, 4096, data)
        val bytes = msg.toByteArray()
        assertEquals(24 + data.size, bytes.size)
    }

    @Test
    fun testRoundTripCarriesTheSameFrame() {
        val original = AdbMessage(
            AdbMessage.A_WRTE,
            7,
            3,
            "hello TV".toByteArray(Charsets.UTF_8)
        )
        val decoded = AdbMessage.readFrom(ByteArrayInputStream(original.toByteArray()))

        assertEquals(original.command, decoded.command)
        assertEquals(original.arg0, decoded.arg0)
        assertEquals(original.arg1, decoded.arg1)
        assertArrayEquals(original.data, decoded.data)
    }

    @Test
    fun testEmptyPayloadIsValid() {
        val decoded = AdbMessage.readFrom(
            ByteArrayInputStream(AdbMessage(AdbMessage.A_OKAY, 1, 2, ByteArray(0)).toByteArray())
        )
        assertEquals(0, decoded.data.size)
    }

    @Test
    fun testUnknownCommandIsRejected() {
        val frame = header(command = 0x12345678, magic = 0x12345678.inv(), length = 0)
        assertThrows(AdbProtocolException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame))
        }
    }

    @Test
    fun testWrongMagicIsRejected() {
        val frame = header(command = AdbMessage.A_CNXN, magic = 0, length = 0)
        val error = assertThrows(AdbProtocolException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame))
        }
        assertTrue(error.message!!.contains("magic"))
    }

    @Test
    fun testNegativePayloadLengthIsRejected() {
        val frame = header(command = AdbMessage.A_CNXN, magic = AdbMessage.A_CNXN.inv(), length = -1)
        val error = assertThrows(AdbProtocolException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame))
        }
        assertTrue(error.message!!.contains("Negative"))
    }

    @Test
    fun testOversizedPayloadLengthIsRejectedBeforeAllocation() {
        val frame = header(
            command = AdbMessage.A_CNXN,
            magic = AdbMessage.A_CNXN.inv(),
            length = AdbMessage.MAX_PAYLOAD + 1
        )
        val error = assertThrows(AdbProtocolException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame))
        }
        assertTrue(error.message!!.contains("4096"))
    }

    @Test
    fun testInvalidChecksumIsRejected() {
        val data = "abc".toByteArray(Charsets.UTF_8)
        val frame = header(
            command = AdbMessage.A_CNXN,
            magic = AdbMessage.A_CNXN.inv(),
            length = data.size,
            checksum = AdbMessage.generateChecksum(data) + 1,
            data = data
        )
        val error = assertThrows(AdbProtocolException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame))
        }
        assertTrue(error.message!!.contains("checksum"))
    }

    @Test
    fun testTruncatedHeaderIsAnEofNotASuccess() {
        val frame = header(command = AdbMessage.A_CNXN, magic = AdbMessage.A_CNXN.inv(), length = 0)
        assertThrows(EOFException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame.copyOf(10)))
        }
    }

    @Test
    fun testTruncatedPayloadIsAnEofNotASuccess() {
        val data = "hello".toByteArray(Charsets.UTF_8)
        val frame = header(
            command = AdbMessage.A_WRTE,
            magic = AdbMessage.A_WRTE.inv(),
            length = data.size,
            data = data
        )
        assertThrows(EOFException::class.java) {
            AdbMessage.readFrom(ByteArrayInputStream(frame.copyOf(frame.size - 2)))
        }
    }

    @Test
    fun testOutgoingOversizedPayloadIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbMessage(AdbMessage.A_WRTE, 1, 0, ByteArray(AdbMessage.MAX_PAYLOAD + 1))
        }
    }

    @Test
    fun testOutgoingUnknownCommandIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbMessage(0x12345678, 1, 0, ByteArray(0))
        }
    }

    @Test
    fun testStreamThatMakesNoProgressIsAnIOException() {
        val frame = header(command = AdbMessage.A_CNXN, magic = AdbMessage.A_CNXN.inv(), length = 4)
        val stuck = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        // The header arrives, then the payload stream returns 0 forever.
        val headerThenStuck = object : java.io.InputStream() {
            private var index = 0
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index < frame.size) {
                    val count = minOf(len, frame.size - index)
                    System.arraycopy(frame, index, b, off, count)
                    index += count
                    return count
                }
                return stuck.read(b, off, len)
            }
        }
        assertThrows(IOException::class.java) {
            AdbMessage.readFrom(headerThenStuck)
        }
    }

    private fun header(
        command: Int,
        magic: Int,
        length: Int,
        checksum: Int = 0,
        data: ByteArray = ByteArray(0)
    ): ByteArray {
        val buffer = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(0)
        buffer.putInt(0)
        buffer.putInt(length)
        buffer.putInt(checksum)
        buffer.putInt(magic)
        buffer.put(data)
        return buffer.array()
    }
}
