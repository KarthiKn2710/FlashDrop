package com.flashdrop.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room schema (blueprint section 9). Only meaningful state transitions and periodic checkpoints
 * are persisted; file contents and private keys are never stored here.
 */

@Entity(tableName = "transfer_sessions", indices = [Index("updatedAt")])
data class TransferSessionEntity(
    @PrimaryKey val sessionId: String,
    val remoteDeviceId: String,
    val remoteDeviceName: String,
    /** Hex SHA-256 of the peer's public key; a resumed session must present the same key. */
    val remoteFingerprint: String,
    val direction: String,
    val transport: String,
    val status: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val totalFiles: Int,
    val manifestDigest: String,
    /** SAF tree the session writes to, or null for the default Download/FlashDrop. */
    val destinationTree: String?,
    val message: String?,
    val averageSpeed: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "transfer_files",
    primaryKeys = ["sessionId", "fileId"],
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TransferFileEntity(
    val sessionId: String,
    val fileId: String,
    val position: Int,
    val fileName: String,
    val relativePath: String,
    val fileSize: Long,
    val mimeType: String,
    val sha256: String?,
    val status: String,
    val completedBytes: Long,
    val isDirectory: Boolean,
    val sourceUri: String?,
    val temporaryUri: String?,
    val destinationUri: String?,
)

@Entity(
    tableName = "transfer_checkpoints",
    primaryKeys = ["sessionId", "fileId"],
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TransferCheckpointEntity(
    val sessionId: String,
    val fileId: String,
    val checkpointVersion: Int,
    val chunkSize: Int,
    /** Verified, durable byte ranges as "start-end" pairs separated by commas (end exclusive). */
    val verifiedRanges: String,
    val committedBytes: Long,
    val updatedAt: Long,
)

@Entity(tableName = "trusted_devices")
data class TrustedDeviceEntity(
    /** Hex public-key fingerprint; display names are never used as identity. */
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val publicKeyFingerprint: String,
    val trustedAt: Long,
    val lastSeenAt: Long,
)
