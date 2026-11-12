package com.flashdrop.core.model

enum class TransferDirection { SEND, RECEIVE }

enum class TransportType { LAN, WIFI_DIRECT, WIFI_AWARE, NEARBY }

/** FDTP session state machine (blueprint section 5.8). */
enum class TransferStatus {
    Idle, Discovering, Connecting, Authenticating, Negotiating, AwaitingApproval,
    Transferring, Verifying, Completed,
    Rejected, Paused, Interrupted, Reconnecting, Failed, Retrying, Cancelled;

    val isTerminal: Boolean
        get() = this == Completed || this == Rejected || this == Failed || this == Cancelled

    val isActive: Boolean get() = !isTerminal && this != Idle
}

enum class FileStatus { Pending, Transferring, Verifying, Completed, Failed }

/** A peer found by discovery. [id] is ephemeral and unauthenticated; never treat it as identity. */
data class RemoteDevice(
    val id: String,
    val displayName: String,
    val transport: TransportType,
    val host: String,
    val port: Int,
    val protocolMajor: Int = 1,
)

/** One item picked for sending. [relativePath] includes the file name and uses '/' separators. */
data class SelectedItem(
    val uri: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mimeType: String,
    val isDirectory: Boolean = false,
)

data class FileTransferState(
    val fileId: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val completedBytes: Long,
    val status: FileStatus,
    val isDirectory: Boolean = false,
)

/** Shown on both devices; the users compare [code] before confirming. */
data class PairingPrompt(
    val peerName: String,
    val code: String,
    val fingerprint: String,
)

data class IncomingRequest(
    val peerName: String,
    val trusted: Boolean,
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
    val previewNames: List<String>,
    val destinationLabel: String,
)

/** Throttled, immutable view of the active session for the UI. */
data class TransferSnapshot(
    val sessionId: String,
    val direction: TransferDirection,
    val peerName: String,
    val transport: TransportType,
    val status: TransferStatus,
    val totalBytes: Long,
    val transferredBytes: Long,
    val speedBytesPerSecond: Long,
    val remainingSeconds: Long?,
    val files: List<FileTransferState>,
    val currentFile: String?,
    val pairing: PairingPrompt?,
    val incoming: IncomingRequest?,
    val message: String?,
    val failedFiles: Int,
    val averageSpeed: Long,
    val elapsedMillis: Long,
    val destinationLabel: String?,
    val destinationUri: String?,
    val canRetry: Boolean,
    /** False when the transfer is paused only because the other device paused it. */
    val pausedLocally: Boolean = false,
) {
    val canPause: Boolean get() = status == TransferStatus.Transferring
    val canResume: Boolean get() = status == TransferStatus.Paused && pausedLocally
    val canCancel: Boolean get() = !status.isTerminal
    val progress: Float
        get() = if (totalBytes <= 0L) 0f else (transferredBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

data class ReceiveState(
    val visible: Boolean = false,
    val deviceName: String = "",
    val addresses: List<String> = emptyList(),
    val port: Int = 0,
    val error: String? = null,
)

data class TransferRecord(
    val sessionId: String,
    val peerName: String,
    val direction: TransferDirection,
    val transport: TransportType,
    val status: TransferStatus,
    val totalBytes: Long,
    val transferredBytes: Long,
    val totalFiles: Int,
    val averageSpeed: Long,
    val message: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

data class TransferRecordDetail(
    val record: TransferRecord,
    val files: List<FileTransferState>,
)

data class TrustedDeviceInfo(
    val deviceId: String,
    val displayName: String,
    val fingerprint: String,
    val trustedAt: Long,
    val lastSeenAt: Long,
)

data class AppSettings(
    val deviceName: String = "",
    val stayVisible: Boolean = false,
    val saveTreeUri: String? = null,
    val saveLabel: String = DEFAULT_SAVE_LABEL,
    val notificationsEnabled: Boolean = true,
    val autoAcceptTrusted: Boolean = false,
) {
    companion object {
        const val DEFAULT_SAVE_LABEL = "Download/FlashDrop"
    }
}

data class Diagnostics(
    val protocolVersion: String,
    val deviceFingerprint: String,
    val transports: List<TransportDiagnostic>,
)

data class TransportDiagnostic(val type: TransportType, val implemented: Boolean, val available: Boolean)
