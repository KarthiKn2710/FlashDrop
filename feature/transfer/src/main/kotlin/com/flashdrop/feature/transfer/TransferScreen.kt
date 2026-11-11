package com.flashdrop.feature.transfer

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdProgressBar
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.FileProgressRow
import com.flashdrop.core.designsystem.IconBadge
import com.flashdrop.core.designsystem.PrimaryButton
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.StatusPill
import com.flashdrop.core.designsystem.statusColor
import com.flashdrop.core.designsystem.statusLabel
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.IncomingRequest
import com.flashdrop.core.model.PairingPrompt
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TransferController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val controller: TransferController,
    private val settings: SettingsRepository,
) : ViewModel() {
    val session: StateFlow<TransferSnapshot?> = controller.session

    fun confirmPairing(accept: Boolean, trust: Boolean) = controller.confirmPairing(accept, trust)
    fun respond(accept: Boolean) = controller.respondToIncoming(accept)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun cancel() = controller.cancel()
    fun retry() = controller.retry()

    fun done() {
        val wasReceive = controller.session.value?.direction == TransferDirection.RECEIVE
        controller.clearFinished()
        if (wasReceive) {
            viewModelScope.launch {
                if (!settings.settings.first().stayVisible) controller.stopReceiving()
            }
        }
    }
}

/** One destination for the whole session; what it shows is driven purely by session state. */
@Composable
fun TransferScreen(onBack: () -> Unit, onDone: () -> Unit, viewModel: TransferViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val s = session
    if (s == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val pairing = s.pairing
    val incoming = s.incoming
    val title = when {
        pairing != null -> "Pair devices"
        incoming != null -> "Incoming transfer"
        s.status.isTerminal -> "Summary"
        s.direction == TransferDirection.SEND -> "Sending"
        else -> "Receiving"
    }
    FdScaffold(title = title, onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            when {
                pairing != null -> PairingView(pairing, s.direction, viewModel::confirmPairing)
                incoming != null -> IncomingView(incoming, viewModel::respond)
                s.status.isTerminal -> CompletionView(s, onDone = { viewModel.done(); onDone() }, onRetry = viewModel::retry)
                else -> ActiveView(s, viewModel)
            }
        }
    }
}

@Composable
private fun PairingView(prompt: PairingPrompt, direction: TransferDirection, onDecision: (Boolean, Boolean) -> Unit) {
    var trust by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        IconBadge(Icons.Rounded.Lock, size = 64)
        Spacer(Modifier.height(20.dp))
        Text(prompt.peerName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            if (direction == TransferDirection.SEND) "wants to receive your files" else "wants to connect",
            style = MaterialTheme.typography.bodyMedium,
            color = FdColors.TextSecondary,
        )
        Spacer(Modifier.height(28.dp))
        FdCard(Modifier.fillMaxWidth(), color = FdColors.Elevated) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                SectionLabel("Verification code")
                Spacer(Modifier.height(8.dp))
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = FdColors.Accent,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Key ${prompt.fingerprint}", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Confirm only if the other device shows exactly the same code. A different code means someone may be intercepting the connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = FdColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = trust,
                onCheckedChange = { trust = it },
                colors = CheckboxDefaults.colors(checkedColor = FdColors.Accent, checkmarkColor = FdColors.OnAccent),
            )
            Text("Trust this device and skip the code next time", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", { onDecision(false, false) }, Modifier.weight(1f))
            PrimaryButton("Codes match", { onDecision(true, trust) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun IncomingView(request: IncomingRequest, onDecision: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        FdCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.PhoneAndroid, size = 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(request.peerName, style = MaterialTheme.typography.titleMedium)
                    Text("wants to send you files", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                }
                StatusPill(if (request.trusted) "Trusted" else "Verified code", if (request.trusted) FdColors.Success else FdColors.Accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        FdCard(Modifier.fillMaxWidth()) {
            Row {
                Stat("Files", request.fileCount.toString(), Modifier.weight(1f))
                Stat("Folders", request.folderCount.toString(), Modifier.weight(1f))
                Stat("Total size", Formatters.bytes(request.totalBytes), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            for (name in request.previewNames) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val more = request.fileCount - request.previewNames.size
            if (more > 0) Text("and $more more", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
        }
        Spacer(Modifier.height(12.dp))
        FdCard(Modifier.fillMaxWidth()) {
            SectionLabel("Save to")
            Spacer(Modifier.height(4.dp))
            Text(request.destinationLabel, style = MaterialTheme.typography.titleSmall)
            Text(
                "Files are checked for integrity before they appear. Nothing is opened or installed automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = FdColors.TextSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Decline", { onDecision(false) }, Modifier.weight(1f))
            PrimaryButton("Accept", { onDecision(true) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun ActiveView(s: TransferSnapshot, viewModel: TransferViewModel) {
    val moving = s.status == TransferStatus.Transferring || s.status == TransferStatus.Paused ||
        s.status == TransferStatus.Verifying || s.status == TransferStatus.Interrupted || s.status == TransferStatus.Reconnecting
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FdCard(Modifier.fillMaxWidth(), color = FdColors.Elevated) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (s.direction == TransferDirection.SEND) "To " else "From ") + s.peerName,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("Encrypted · " + com.flashdrop.core.designsystem.transportLabel(s.transport), style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                        }
                        StatusPill(statusLabel(s.status), statusColor(s.status))
                    }
                    Spacer(Modifier.height(20.dp))
                    if (moving) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${Formatters.bytes(s.transferredBytes)} of ${Formatters.bytes(s.totalBytes)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FdColors.TextSecondary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        FdProgressBar(s.progress, color = statusColor(s.status))
                        Spacer(Modifier.height(14.dp))
                        Row {
                            Stat("Speed", if (s.status == TransferStatus.Transferring) Formatters.speed(s.speedBytesPerSecond) else "—", Modifier.weight(1f))
                            Stat("Time left", s.remainingSeconds?.let(Formatters::duration) ?: "—", Modifier.weight(1f))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = FdColors.Accent)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                when (s.status) {
                                    TransferStatus.AwaitingApproval ->
                                        if (s.direction == TransferDirection.SEND) "Waiting for ${s.peerName} to accept" else "Preparing"
                                    TransferStatus.Authenticating -> "Securing the connection"
                                    TransferStatus.Negotiating -> "Preparing the transfer"
                                    else -> "Connecting to ${s.peerName}"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    s.message?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = FdColors.Warning)
                    }
                }
            }
            if (s.currentFile != null && moving) {
                item { Text("Current: ${s.currentFile}", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            item { SectionLabel("Files", Modifier.padding(top = 4.dp)) }
            items(s.files, key = { it.fileId }) { file -> FileProgressRow(file) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", viewModel::cancel, Modifier.weight(1f), icon = Icons.Rounded.Close, color = FdColors.Error)
            if (s.canResume) {
                PrimaryButton("Resume", viewModel::resume, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
            } else {
                PrimaryButton("Pause", viewModel::pause, Modifier.weight(1f), enabled = s.canPause, icon = Icons.Rounded.Pause)
            }
        }
    }
}

@Composable
private fun CompletionView(s: TransferSnapshot, onDone: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val success = s.status == TransferStatus.Completed
    val partial = success && s.failedFiles > 0
    val color = when {
        partial -> FdColors.Warning
        success -> FdColors.Success
        s.status == TransferStatus.Cancelled -> FdColors.TextSecondary
        else -> FdColors.Error
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconBadge(if (success && !partial) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, tint = color, size = 72)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when {
                            partial -> "Finished with errors"
                            success -> if (s.direction == TransferDirection.SEND) "Sent" else "Received"
                            else -> statusLabel(s.status)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        s.message ?: ((if (s.direction == TransferDirection.SEND) "to " else "from ") + s.peerName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FdColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item {
                FdCard(Modifier.fillMaxWidth()) {
                    Row {
                        Stat("Transferred", Formatters.bytes(s.transferredBytes), Modifier.weight(1f))
                        Stat("Average speed", if (s.averageSpeed > 0) Formatters.speed(s.averageSpeed) else "—", Modifier.weight(1f))
                        Stat("Time", Formatters.duration(s.elapsedMillis / 1000), Modifier.weight(1f))
                    }
                    if (success) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (partial) "${s.failedFiles} failed · the rest passed SHA-256 verification" else "All files passed SHA-256 verification",
                            style = MaterialTheme.typography.bodySmall,
                            color = color,
                        )
                    }
                }
            }
            if (s.files.isNotEmpty()) {
                item { SectionLabel("Files", Modifier.padding(top = 4.dp)) }
                items(s.files, key = { it.fileId }) { file -> FileProgressRow(file) }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (success && s.direction == TransferDirection.RECEIVE) {
                SecondaryButton(
                    "Open ${s.destinationLabel ?: "destination"}",
                    { openDestination(context, s.destinationUri) },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.FolderOpen,
                )
            }
            if (s.canRetry) {
                SecondaryButton("Reconnect and resume", onRetry, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh)
            }
            PrimaryButton("Done", onDone, Modifier.fillMaxWidth())
        }
    }
}

/** Shows the folder in the system file manager; received files are never opened automatically. */
private fun openDestination(context: Context, rootUri: String?) {
    val intent = if (rootUri == null) {
        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    } else {
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(rootUri), DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(context, "No file manager available", Toast.LENGTH_SHORT).show()
    }
}
