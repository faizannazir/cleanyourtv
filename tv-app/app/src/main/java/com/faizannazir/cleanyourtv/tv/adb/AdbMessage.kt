package com.faizannazir.cleanyourtv.tv.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A malformed or unexpected frame from the ADB peer.
 *
 * This is deliberately an [Exception] rather than a silent `null`: callers must treat it as a
 * failed/uncertain operation, never as a successful empty response.
 */
class AdbProtocolException(message: String) : Exception(message)

/**
 * One ADB wire frame: a 24-byte little-endian header followed by at most [MAX_PAYLOAD] data bytes.
 *
 * The advertised protocol version is [A_VERSION] (`0x01000000`). Checksums are mandatory at that
 * version: the peer computes them for every packet, so a wrong checksum means the frame is corrupt.
 */
data class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val data: ByteArray
) {
    init {
        require(command in VALID_COMMANDS) {
            "Unsupported ADB command: 0x${command.toHex()}"
        }
        require(data.size <= MAX_PAYLOAD) {
            "ADB payload of ${data.size} bytes exceeds the advertised $MAX_PAYLOAD-byte limit"
        }
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(data.size)
        buffer.putInt(generateChecksum(data))
        buffer.putInt(magicFor(command))
        buffer.put(data)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AdbMessage
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + arg0
        result = 31 * result + arg1
        result = 31 * result + data.contentHashCode()
        return result
    }

    companion object {
        const val A_SYNC = 0x434e5953
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x534c5453

        const val AUTH_TYPE_TOKEN = 1
        const val AUTH_TYPE_SIGNATURE = 2
        const val AUTH_TYPE_RSA_PUBLIC = 3

        /**
         * Original ADB protocol revision. Checksums are mandatory below `0x01000001`, which is
         * exactly the version we advertise, so every incoming frame must carry a valid checksum.
         */
        const val A_VERSION = 0x01000000

        /** The payload size we advertise in our `CNXN` packet and enforce on every frame. */
        const val MAX_PAYLOAD = 4096

        const val HEADER_SIZE = 24

        val VALID_COMMANDS: Set<Int> = setOf(
            A_SYNC, A_CNXN, A_AUTH, A_OPEN, A_OKAY, A_CLSE, A_WRTE, A_STLS
        )

        fun magicFor(command: Int): Int = command.inv()

        /** Sum of the payload bytes, treating every byte as unsigned (0..255). */
        fun generateChecksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) {
                sum += b.toInt() and 0xFF
            }
            return sum
        }

        /**
         * Reads exactly one frame from [input].
         *
         * The header is validated *before* the payload is allocated or read, so an oversized or
         * negative peer-controlled length can never cause a large allocation. A short read is an
         * [EOFException] (incomplete response), never a partial success.
         */
        @Throws(AdbProtocolException::class, EOFException::class, IOException::class)
        fun readFrom(input: InputStream): AdbMessage {
            val header = readExactly(input, HEADER_SIZE)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            val command = buffer.int
            val arg0 = buffer.int
            val arg1 = buffer.int
            val dataLength = buffer.int
            val checksum = buffer.int
            val magic = buffer.int

            if (command !in VALID_COMMANDS) {
                throw AdbProtocolException("Unknown ADB command: 0x${command.toHex()}")
            }
            if (magic != magicFor(command)) {
                throw AdbProtocolException(
                    "Invalid ADB magic: 0x${magic.toHex()} for command 0x${command.toHex()}"
                )
            }
            if (dataLength < 0) {
                throw AdbProtocolException("Negative ADB payload length: $dataLength")
            }
            if (dataLength > MAX_PAYLOAD) {
                throw AdbProtocolException(
                    "ADB payload length $dataLength exceeds the advertised $MAX_PAYLOAD-byte limit"
                )
            }

            val data = if (dataLength == 0) {
                ByteArray(0)
            } else {
                readExactly(input, dataLength)
            }

            val expectedChecksum = generateChecksum(data)
            if (checksum != expectedChecksum) {
                throw AdbProtocolException(
                    "Invalid ADB checksum: got 0x${checksum.toHex()}, expected 0x${expectedChecksum.toHex()}"
                )
            }

            return AdbMessage(command, arg0, arg1, data)
        }

        private fun readExactly(input: InputStream, size: Int): ByteArray {
            val data = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(data, offset, size - offset)
                if (count < 0) {
                    throw EOFException("ADB stream ended after $offset of $size bytes")
                }
                if (count == 0) {
                    throw IOException("ADB stream made no progress while reading $size bytes")
                }
                offset += count
            }
            return data
        }
    }
}

internal fun Int.toHex(): String = "0x" + Integer.toHexString(this)
