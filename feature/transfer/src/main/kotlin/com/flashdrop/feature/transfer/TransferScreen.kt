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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.flashdrop.core.designsystem.DeviceAvatar
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdMotion
import com.flashdrop.core.designsystem.Haptic
import com.flashdrop.core.designsystem.HapticOnChange
import com.flashdrop.core.designsystem.OnChange
import com.flashdrop.core.designsystem.ResultMark
import com.flashdrop.core.designsystem.rememberHaptics
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdProgressBar
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.FileProgressRow
import com.flashdrop.core.designsystem.IconBadge
import com.flashdrop.core.designsystem.PrimaryButton
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.StatRow
import com.flashdrop.core.designsystem.StatusPill
import com.flashdrop.core.designsystem.statusColor
import com.flashdrop.core.designsystem.statusLabel
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.IncomingRequest
import com.flashdrop.core.model.PairingPrompt
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.domain.repository.SelectionRepository
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
    private val selection: SelectionRepository,
) : ViewModel() {
    val session: StateFlow<TransferSnapshot?> = controller.session

    fun confirmPairing(accept: Boolean, trust: Boolean) = controller.confirmPairing(accept, trust)
    fun respond(accept: Boolean) = controller.respondToIncoming(accept)
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun cancel() = controller.cancel()
    fun retry() = controller.retry()

    fun done() {
        val finished = controller.session.value
        val wasReceive = finished?.direction == TransferDirection.RECEIVE
        // A delivered selection is done with; keep it after a failure so it can be sent again.
        if (finished?.direction == TransferDirection.SEND && finished.status == TransferStatus.Completed && finished.failedFiles == 0) {
            selection.clear()
        }
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
    val phase = when {
        pairing != null -> Phase.Pairing
        incoming != null -> Phase.Incoming
        s.status.isTerminal -> Phase.Done
        else -> Phase.Active
    }
    val haptics = rememberHaptics()
    // Something needs the user's decision: a nudge, even if they were looking elsewhere.
    HapticOnChange(phase, Haptic.Tick) { it == Phase.Pairing || it == Phase.Incoming }
    OnChange(s.status) { status ->
        when {
            status == TransferStatus.Completed && s.failedFiles == 0 -> haptics.perform(Haptic.Success)
            status == TransferStatus.Completed || status == TransferStatus.Failed ||
                status == TransferStatus.Rejected -> haptics.perform(Haptic.Error)
            status == TransferStatus.Interrupted -> haptics.perform(Haptic.Tick)
            status == TransferStatus.Paused && !s.pausedLocally -> haptics.perform(Haptic.Tick)
            else -> Unit
        }
    }

    FdScaffold(title = title, onBack = onBack) { padding ->
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
            transitionSpec = {
                (fadeIn(tween(FdMotion.MEDIUM, delayMillis = 60)) + scaleIn(tween(FdMotion.MEDIUM, easing = FdMotion.Enter), initialScale = 0.96f)) togetherWith
                    fadeOut(tween(FdMotion.SHORT))
            },
            label = "transferPhase",
        ) { current ->
            Box(Modifier.fillMaxSize()) {
                when (current) {
                    Phase.Pairing -> pairing?.let { PairingView(it, s.direction, viewModel::confirmPairing) }
                    Phase.Incoming -> incoming?.let { IncomingView(it, viewModel::respond) }
                    Phase.Done -> CompletionView(s, onDone = { viewModel.done(); onDone() }, onRetry = viewModel::retry)
                    Phase.Active -> ActiveView(s, viewModel)
                }
            }
        }
    }
}

private enum class Phase { Pairing, Incoming, Active, Done }

@Composable
private fun PairingView(prompt: PairingPrompt, direction: TransferDirection, onDecision: (Boolean, Boolean) -> Unit) {
    var trust by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
                // Already large: kept at a fixed size so very large font settings cannot push it
                // off screen. Read digit by digit, so it can be compared by ear too.
                val fontScale = LocalDensity.current.fontScale
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    modifier = Modifier.semantics {
                        contentDescription = "Verification code " + prompt.code.toList().joinToString(" ")
                    },
                    fontSize = (44f / fontScale.coerceAtLeast(1f)).sp,
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
        // The whole row is the touch target and reads as one checkbox.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = trust, role = Role.Checkbox) {
                    haptics.perform(Haptic.Toggle)
                    trust = it
                }
                .padding(vertical = 4.dp),
        ) {
            Checkbox(
                checked = trust,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = FdColors.Accent, checkmarkColor = FdColors.OnAccent),
                modifier = Modifier.padding(12.dp),
            )
            Text("Trust this device and skip the code next time", style = MaterialTheme.typography.bodyMedium)
        }
    }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", { onDecision(false, false) }, Modifier.weight(1f), haptic = Haptic.Reject)
            PrimaryButton("Codes match", { onDecision(true, trust) }, Modifier.weight(1f), haptic = Haptic.Confirm)
        }
    }
}

@Composable
private fun IncomingView(request: IncomingRequest, onDecision: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        FdCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(request.peerName, size = 48.dp)
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
            StatRow(
                listOf(
                    "Files" to request.fileCount.toString(),
                    "Folders" to request.folderCount.toString(),
                    "Total size" to Formatters.bytes(request.totalBytes),
                ),
            )
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
    }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Decline", { onDecision(false) }, Modifier.weight(1f), haptic = Haptic.Reject)
            PrimaryButton("Accept", { onDecision(true) }, Modifier.weight(1f), haptic = Haptic.Confirm)
        }
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
                        StatusPill(statusLabel(s.status), statusColor(s.status), announce = true)
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
                        FdProgressBar(s.progress, color = statusColor(s.status), label = "Overall progress")
                        Spacer(Modifier.height(14.dp))
                        StatRow(
                            listOf(
                                "Speed" to if (s.status == TransferStatus.Transferring) Formatters.speed(s.speedBytesPerSecond) else "—",
                                "Time left" to (s.remainingSeconds?.let(Formatters::duration) ?: "—"),
                            ),
                        )
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
            items(s.files, key = { it.fileId }) { file -> FileProgressRow(file, Modifier.animateItem()) }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", viewModel::cancel, Modifier.weight(1f), icon = Icons.Rounded.Close, color = FdColors.Error, haptic = Haptic.Reject)
            if (s.canResume) {
                PrimaryButton("Resume", viewModel::resume, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow, haptic = Haptic.Toggle)
            } else {
                PrimaryButton("Pause", viewModel::pause, Modifier.weight(1f), enabled = s.canPause, icon = Icons.Rounded.Pause, haptic = Haptic.Toggle)
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
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (s.status == TransferStatus.Cancelled) {
                        IconBadge(Icons.Rounded.ErrorOutline, tint = color, size = 72)
                    } else {
                        ResultMark(success = success && !partial)
                    }
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
                    StatRow(
                        listOf(
                            "Transferred" to Formatters.bytes(s.transferredBytes),
                            "Average speed" to if (s.averageSpeed > 0) Formatters.speed(s.averageSpeed) else "—",
                            "Time" to Formatters.duration(s.elapsedMillis / 1000),
                        ),
                    )
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
                SecondaryButton("Reconnect and resume", onRetry, Modifier.fillMaxWidth(), icon = Icons.Rounded.Refresh, haptic = Haptic.Confirm)
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
