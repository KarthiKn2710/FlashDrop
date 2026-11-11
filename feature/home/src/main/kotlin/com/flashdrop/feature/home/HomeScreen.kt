package com.flashdrop.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdProgressBar
import com.flashdrop.core.designsystem.IconBadge
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.StatusPill
import com.flashdrop.core.designsystem.TransferRecordRow
import com.flashdrop.core.designsystem.statusColor
import com.flashdrop.core.designsystem.statusLabel
import com.flashdrop.core.model.ReceiveState
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.domain.repository.TransferController
import com.flashdrop.domain.usecase.ObserveRecentTransfersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val session: TransferSnapshot? = null,
    val receiving: ReceiveState = ReceiveState(),
    val recent: List<TransferRecord> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    controller: TransferController,
    recentTransfers: ObserveRecentTransfersUseCase,
) : ViewModel() {
    val state: StateFlow<HomeUiState> =
        combine(controller.session, controller.receiving, recentTransfers(5), ::HomeUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}

@Composable
fun HomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onOpenTransfer: () -> Unit,
    onHistory: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Bolt, size = 36)
                Spacer(Modifier.width(10.dp))
                Text("FlashDrop", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onHistory) {
                    Icon(Icons.Rounded.History, contentDescription = "History", tint = FdColors.TextSecondary)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = FdColors.TextSecondary)
                }
            }
        }

        item {
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                Text("Fast, private transfers\nbetween nearby devices.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                if (state.receiving.visible) {
                    StatusPill("Visible as ${state.receiving.deviceName}", FdColors.Success)
                } else {
                    StatusPill("Not visible to other devices", FdColors.TextSecondary)
                }
            }
        }

        val session = state.session
        if (session != null) {
            item { ActiveTransferCard(session, onOpenTransfer) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard("Send", "Pick files or folders", Icons.Rounded.Upload, FdColors.Accent, onSend, Modifier.weight(1f))
                ActionCard("Receive", "Become visible nearby", Icons.Rounded.Download, FdColors.Success, onReceive, Modifier.weight(1f))
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Recent transfers", Modifier.weight(1f))
                if (state.recent.isNotEmpty()) TextButton(onClick = onHistory) { Text("See all") }
            }
        }

        if (state.recent.isEmpty()) {
            item {
                FdCard(Modifier.fillMaxWidth()) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Transfers you send and receive will appear here. No account or internet connection is needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FdColors.TextSecondary,
                    )
                }
            }
        } else {
            items(state.recent, key = { it.sessionId }) { record ->
                TransferRecordRow(
                    record = record,
                    time = DateUtils.getRelativeTimeSpanString(record.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    onClick = { onOpenRecord(record.sessionId) },
                )
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: ImageVector, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FdCard(modifier = modifier, onClick = onClick, contentPadding = PaddingValues(18.dp)) {
        IconBadge(icon, tint = tint, size = 48)
        Spacer(Modifier.height(28.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
    }
}

@Composable
private fun ActiveTransferCard(session: TransferSnapshot, onClick: () -> Unit) {
    FdCard(Modifier.fillMaxWidth(), onClick = onClick, color = FdColors.Elevated) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (session.direction == TransferDirection.SEND) "Sending to " else "Receiving from ") + session.peerName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    session.message ?: "Tap to open",
                    style = MaterialTheme.typography.bodySmall,
                    color = FdColors.TextSecondary,
                    maxLines = 1,
                )
            }
            StatusPill(statusLabel(session.status), statusColor(session.status))
        }
        if (!session.status.isTerminal) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth()) { FdProgressBar(session.progress) }
        }
    }
}
