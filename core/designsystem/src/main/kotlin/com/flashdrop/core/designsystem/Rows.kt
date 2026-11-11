package com.flashdrop.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flashdrop.core.model.FileStatus
import com.flashdrop.core.model.FileTransferState
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.core.model.TransferStatus

fun statusLabel(status: TransferStatus): String = when (status) {
    TransferStatus.Idle -> "Idle"
    TransferStatus.Discovering -> "Searching"
    TransferStatus.Connecting -> "Connecting"
    TransferStatus.Authenticating -> "Securing"
    TransferStatus.Negotiating -> "Preparing"
    TransferStatus.AwaitingApproval -> "Waiting for approval"
    TransferStatus.Transferring -> "Transferring"
    TransferStatus.Verifying -> "Verifying"
    TransferStatus.Completed -> "Completed"
    TransferStatus.Rejected -> "Declined"
    TransferStatus.Paused -> "Paused"
    TransferStatus.Interrupted -> "Interrupted"
    TransferStatus.Reconnecting -> "Reconnecting"
    TransferStatus.Failed -> "Failed"
    TransferStatus.Retrying -> "Retrying"
    TransferStatus.Cancelled -> "Cancelled"
}

fun statusColor(status: TransferStatus): Color = when (status) {
    TransferStatus.Completed -> FdColors.Success
    TransferStatus.Failed, TransferStatus.Rejected -> FdColors.Error
    TransferStatus.Cancelled -> FdColors.TextSecondary
    TransferStatus.Paused, TransferStatus.Interrupted, TransferStatus.Reconnecting, TransferStatus.Retrying -> FdColors.Warning
    else -> FdColors.Accent
}

fun fileIcon(name: String, isDirectory: Boolean = false): ImageVector {
    if (isDirectory) return Icons.Rounded.Folder
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Rounded.Image
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> Icons.Rounded.Movie
        "mp3", "wav", "flac", "aac", "ogg", "m4a" -> Icons.Rounded.AudioFile
        "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "md" -> Icons.Rounded.Description
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
fun TransferRecordRow(record: TransferRecord, time: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val sent = record.direction == TransferDirection.SEND
    FdCard(modifier = modifier.fillMaxWidth(), onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                icon = if (sent) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                tint = if (sent) FdColors.Accent else FdColors.Success,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.peerName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${record.totalFiles} ${if (record.totalFiles == 1) "file" else "files"} · ${Formatters.bytes(record.totalBytes)} · $time",
                    style = MaterialTheme.typography.bodySmall,
                    color = FdColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(statusLabel(record.status), statusColor(record.status))
        }
    }
}

@Composable
fun FileProgressRow(file: FileTransferState, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(fileIcon(file.name, file.isDirectory), contentDescription = null, tint = FdColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.relativePath, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (file.status == FileStatus.Transferring && file.size > 0) {
                FdProgressBar(file.completedBytes.toFloat() / file.size, Modifier.height(4.dp))
            } else {
                Text(
                    when (file.status) {
                        FileStatus.Pending -> "Waiting · ${Formatters.bytes(file.size)}"
                        FileStatus.Verifying -> "Verifying"
                        FileStatus.Completed -> "${Formatters.bytes(file.size)} · Verified"
                        FileStatus.Failed -> "Failed"
                        FileStatus.Transferring -> Formatters.bytes(file.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (file.status == FileStatus.Failed) FdColors.Error else FdColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (file.status) {
            FileStatus.Completed -> Icon(Icons.Rounded.CheckCircle, contentDescription = "Completed", tint = FdColors.Success, modifier = Modifier.size(20.dp))
            FileStatus.Failed -> Icon(Icons.Rounded.ErrorOutline, contentDescription = "Failed", tint = FdColors.Error, modifier = Modifier.size(20.dp))
            else -> Unit
        }
    }
}
