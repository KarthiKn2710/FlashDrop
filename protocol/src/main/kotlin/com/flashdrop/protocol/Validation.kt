package com.flashdrop.protocol

import java.security.MessageDigest
import java.security.SecureRandom

object Hashing {
    private val random = SecureRandom()

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
    fun sha256(data: ByteArray): ByteArray = sha256().digest(data)
    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/** Short authentication string shown on both devices (blueprint section 7, "Pairing"). */
object Pairing {
    const val NONCE_SIZE = 32

    fun commitment(nonce: ByteArray): ByteArray = Hashing.sha256(nonce)

    /**
     * Six-digit code bound to both TLS identities and both nonces. A man-in-the-middle presents
     * different keys to each side, so the two devices would display different codes. The client
     * commits to its nonce before seeing the server's, so neither side can steer the result.
     */
    fun shortCode(
        clientFingerprint: ByteArray,
        serverFingerprint: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): String {
        val digest = Hashing.sha256()
        digest.update("FDTP-SAS-v1".toByteArray(Charsets.US_ASCII))
        for (part in arrayOf(clientFingerprint, serverFingerprint, clientNonce, serverNonce)) {
            digest.update(byteArrayOf((part.size ushr 8).toByte(), part.size.toByte()))
            digest.update(part)
        }
        val h = digest.digest()
        val value = (readInt(h, 0).toLong() and 0xFFFFFFFFL) % 1_000_000L
        return "%06d".format(value)
    }
}

data class ReceiveLimits(
    val maxFiles: Int = Fdtp.MAX_MANIFEST_FILES,
    val maxFileBytes: Long = Long.MAX_VALUE,
    val maxTotalBytes: Long = Long.MAX_VALUE,
)

/** Strict manifest and path validation. The receiver never trusts sender-supplied names. */
object ManifestValidator {
    private const val FORBIDDEN = "\\:*?\"<>|"
    private val sessionIdPattern = Regex("[A-Za-z0-9-]{8,64}")

    fun validateSessionId(sessionId: String) {
        if (!sessionIdPattern.matches(sessionId)) invalid("Invalid session id")
    }

    /** Returns the validated path segments, or throws. */
    fun validatePath(path: String): List<String> {
        if (path.isEmpty()) invalid("Empty path")
        if (path.toByteArray(Charsets.UTF_8).size > Fdtp.MAX_PATH_BYTES) invalid("Path too long")
        if (path.startsWith("/") || path.startsWith("\\")) invalid("Absolute path rejected: $path")
        val segments = path.split('/')
        if (segments.size > Fdtp.MAX_PATH_DEPTH) invalid("Path too deep")
        for (segment in segments) validateName(segment)
        return segments
    }

    fun validateName(name: String) {
        if (name.isEmpty()) invalid("Empty path segment")
        if (name == "." || name == "..") invalid("Path traversal rejected")
        if (name.toByteArray(Charsets.UTF_8).size > Fdtp.MAX_NAME_BYTES) invalid("Name too long")
        if (name.trim().isEmpty()) invalid("Blank name")
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN || c == '/') invalid("Invalid character in name")
        }
    }

    fun validate(sessionId: String, files: List<FileEntry>, totalBytes: Long, totalFiles: Int, chunkSize: Int, limits: ReceiveLimits) {
        validateSessionId(sessionId)
        if (files.isEmpty()) invalid("Empty manifest")
        if (files.size > limits.maxFiles) throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Too many files")
        if (totalFiles != files.size) invalid("File count mismatch")

        val ids = HashSet<String>(files.size * 2)
        val paths = HashSet<String>(files.size * 2)
        var sum = 0L
        for (entry in files) {
            if (entry.fileId.isEmpty() || entry.fileId.length > 64 || !ids.add(entry.fileId)) invalid("Duplicate or invalid file id")
            val segments = validatePath(entry.relativePath)
            if (segments.last() != entry.fileName) invalid("File name does not match its path")
            // Case-insensitive, because common destination file systems are.
            if (!paths.add(entry.relativePath.lowercase())) invalid("Duplicate destination path: ${entry.relativePath}")
            when (entry.type) {
                FileType.DIRECTORY -> if (entry.fileSize != 0L) invalid("Directory with a size")
                FileType.REGULAR_FILE -> {
                    if (entry.fileSize < 0) invalid("Negative file size")
                    if (entry.fileSize > limits.maxFileBytes) throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "File too large")
                    if (entry.chunkSize != chunkSize) invalid("Chunk size does not match the negotiated value")
                    if (entry.totalChunks != chunkCount(entry.fileSize, chunkSize)) invalid("Chunk count mismatch")
                    sum += entry.fileSize
                    if (sum < 0) invalid("Total size overflow")
                }
                else -> invalid("Unsupported entry type")
            }
        }
        if (sum != totalBytes) invalid("Total size mismatch")
        if (sum > limits.maxTotalBytes) throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Transfer too large")
    }

    fun chunkCount(size: Long, chunkSize: Int): Long = if (size == 0L) 0 else (size + chunkSize - 1) / chunkSize

    /** Identity of a manifest for resume: independent of paging and of the negotiated chunk size. */
    fun digest(sessionId: String, files: List<FileEntry>): ByteArray {
        val d = Hashing.sha256()
        d.update(sessionId.toByteArray(Charsets.UTF_8))
        for (f in files) {
            d.update(0)
            d.update(f.fileId.toByteArray(Charsets.UTF_8))
            d.update(0)
            d.update(f.relativePath.toByteArray(Charsets.UTF_8))
            d.update(0)
            d.update(f.fileSize.toString().toByteArray(Charsets.US_ASCII))
            d.update(f.type.ordinal.toByte())
        }
        return d.digest()
    }

    private fun invalid(message: String): Nothing = throw ProtocolException(ErrorCode.MANIFEST_INVALID, message)
}
