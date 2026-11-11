package com.flashdrop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class FrameAndValidationTest {

    private fun frames(block: (FrameWriter) -> Unit): ByteArray =
        ByteArrayOutputStream().also { block(FrameWriter(it)) }.toByteArray()

    @Test
    fun headerLayoutMatchesTheSpecification() {
        val bytes = frames { it.write(FrameType.PING, byteArrayOf(9, 8, 7)) }
        assertEquals(16 + 3, bytes.size)
        assertEquals("FDRP", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(1, bytes[4].toInt())                     // version
        assertEquals(FrameType.PING.code, bytes[5].toInt())   // type
        assertEquals(3, readInt(bytes, 8))                    // big-endian payload length
        assertEquals(0, readInt(bytes, 12))                   // sequence
    }

    @Test
    fun roundTripsControlAndDataFrames() {
        val data = ByteArray(1000) { it.toByte() }
        val bytes = frames {
            it.write(FrameType.CLIENT_HELLO, byteArrayOf(1, 2, 3))
            it.writeChunk(7, 5_000_000_000L, data, 1000)
        }
        val reader = FrameReader(ByteArrayInputStream(bytes)).apply { maxDataPayload = 4096 }
        val first = reader.readHeader()!!
        assertEquals(FrameType.CLIENT_HELLO, first.type)
        reader.readPayload(ByteArray(3), 3)

        val second = reader.readHeader()!!
        assertEquals(FrameType.DATA_CHUNK, second.type)
        assertEquals(1, second.sequence)
        val payload = ByteArray(second.length)
        reader.readPayload(payload, second.length)
        assertEquals(7, readInt(payload, 0))
        assertEquals(5_000_000_000L, readLong(payload, 4))
        assertEquals(data.toList(), payload.drop(Fdtp.CHUNK_HEADER))
        assertNull(reader.readHeader()) // clean end of stream
    }

    @Test
    fun oversizedControlPayloadIsRejectedBeforeAllocation() {
        val bytes = frames { it.write(FrameType.PING, ByteArray(0)) }
        // Declare a 512 MiB payload without sending it.
        bytes[8] = 0x20; bytes[9] = 0; bytes[10] = 0; bytes[11] = 0
        val e = assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).readHeader() }
        assertEquals(ErrorCode.LIMIT_EXCEEDED, e.code)
    }

    @Test
    fun dataChunksAreRejectedUntilAChunkSizeIsNegotiated() {
        val bytes = frames { it.writeChunk(0, 0, ByteArray(10), 10) }
        val e = assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(bytes)).readHeader() }
        assertEquals(ErrorCode.LIMIT_EXCEEDED, e.code)
    }

    @Test
    fun malformedFramesAreRejected() {
        val badMagic = frames { it.write(FrameType.PING, ByteArray(0)) }.also { it[0] = 'X'.code.toByte() }
        assertEquals(
            ErrorCode.MALFORMED_FRAME,
            assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(badMagic)).readHeader() }.code,
        )

        val badVersion = frames { it.write(FrameType.PING, ByteArray(0)) }.also { it[4] = 9 }
        assertEquals(
            ErrorCode.UNSUPPORTED_VERSION,
            assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(badVersion)).readHeader() }.code,
        )

        val replayed = frames { it.write(FrameType.PING, ByteArray(0)) }.let { it + it } // same sequence twice
        val reader = FrameReader(ByteArrayInputStream(replayed))
        reader.readHeader()
        assertEquals(ErrorCode.MALFORMED_FRAME, assertThrows(ProtocolException::class.java) { reader.readHeader() }.code)

        val truncated = frames { it.write(FrameType.PING, ByteArray(10)) }.copyOf(20)
        val r2 = FrameReader(ByteArrayInputStream(truncated))
        val header = r2.readHeader()!!
        assertThrows(EOFException::class.java) { r2.readPayload(ByteArray(header.length), header.length) }
    }

    @Test
    fun unknownFrameTypesAreRejectedUnlessMarkedIgnorable() {
        val unknown = frames { it.writeRaw(200, byteArrayOf(1, 2), flags = 0) }
        assertEquals(
            ErrorCode.MALFORMED_FRAME,
            assertThrows(ProtocolException::class.java) { FrameReader(ByteArrayInputStream(unknown)).readHeader() }.code,
        )

        val ignorable = frames {
            it.writeRaw(200, byteArrayOf(1, 2), flags = Fdtp.FLAG_IGNORABLE)
            it.write(FrameType.PONG, ByteArray(0))
        }
        assertEquals(FrameType.PONG, FrameReader(ByteArrayInputStream(ignorable)).readHeader()!!.type)
    }

    @Test
    fun pathValidation() {
        assertEquals(listOf("a", "b c", "d.txt"), ManifestValidator.validatePath("a/b c/d.txt"))
        ManifestValidator.validatePath("日本語/файл.txt")
        for (bad in listOf("", "/etc/passwd", "\\\\server\\share", "..", "a/../b", "a/./b", "a//b", "a/b/", "C:\\x", "a\nb", "x".repeat(300))) {
            val e = assertThrows("'$bad' must be rejected", ProtocolException::class.java) { ManifestValidator.validatePath(bad) }
            assertEquals(ErrorCode.MANIFEST_INVALID, e.code)
        }
    }

    @Test
    fun manifestLimitsAndTotalsAreEnforced() {
        val ok = entriesOf(listOf(testFile("a.bin", 10), testFile("b.bin", 20))).map {
            it.copy(chunkSize = TEST_CHUNK, totalChunks = 1)
        }
        ManifestValidator.validate(SESSION, ok, 30, 2, TEST_CHUNK, ReceiveLimits())

        fun rejected(code: ErrorCode, block: () -> Unit) =
            assertEquals(code, assertThrows(ProtocolException::class.java) { block() }.code)

        rejected(ErrorCode.MANIFEST_INVALID) { ManifestValidator.validate(SESSION, ok, 31, 2, TEST_CHUNK, ReceiveLimits()) }
        rejected(ErrorCode.MANIFEST_INVALID) { ManifestValidator.validate(SESSION, ok, 30, 3, TEST_CHUNK, ReceiveLimits()) }
        rejected(ErrorCode.MANIFEST_INVALID) { ManifestValidator.validate("../../x", ok, 30, 2, TEST_CHUNK, ReceiveLimits()) }
        rejected(ErrorCode.LIMIT_EXCEEDED) { ManifestValidator.validate(SESSION, ok, 30, 2, TEST_CHUNK, ReceiveLimits(maxFiles = 1)) }
        rejected(ErrorCode.LIMIT_EXCEEDED) { ManifestValidator.validate(SESSION, ok, 30, 2, TEST_CHUNK, ReceiveLimits(maxFileBytes = 15)) }
        rejected(ErrorCode.LIMIT_EXCEEDED) { ManifestValidator.validate(SESSION, ok, 30, 2, TEST_CHUNK, ReceiveLimits(maxTotalBytes = 25)) }
        rejected(ErrorCode.MANIFEST_INVALID) {
            ManifestValidator.validate(SESSION, listOf(ok[0], ok[1].copy(fileId = "f0")), 30, 2, TEST_CHUNK, ReceiveLimits())
        }
    }

    @Test
    fun pairingCodeIsBoundToBothIdentitiesAndNonces() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        val attacker = ByteArray(32) { 3 }
        val n1 = ByteArray(32) { 4 }
        val n2 = ByteArray(32) { 5 }
        val code = Pairing.shortCode(a, b, n1, n2)
        assertTrue(code.matches(Regex("\\d{6}")))
        assertEquals(code, Pairing.shortCode(a, b, n1, n2))
        // A man-in-the-middle substitutes its own key towards each side.
        assertNotEquals(code, Pairing.shortCode(a, attacker, n1, n2))
        assertNotEquals(code, Pairing.shortCode(attacker, b, n1, n2))
        assertNotEquals(code, Pairing.shortCode(a, b, n2, n1))
    }
}
