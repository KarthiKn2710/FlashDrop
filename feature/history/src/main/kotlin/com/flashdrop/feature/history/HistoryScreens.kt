package com.flashdrop.feature.history

import android.text.format.DateFormat
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.FileProgressRow
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.StatusPill
import com.flashdrop.core.designsystem.TransferRecordRow
import com.flashdrop.core.designsystem.statusColor
import com.flashdrop.core.designsystem.statusLabel
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.core.model.TransferRecordDetail
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.domain.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

data class HistoryGroup(val label: String, val records: List<TransferRecord>)

@HiltViewModel
class HistoryViewModel @Inject constructor(private val history: HistoryRepository) : ViewModel() {
    val groups: StateFlow<List<HistoryGroup>?> = history.records().map { records ->
        records.groupBy { dayLabel(it.createdAt) }.map { (label, items) -> HistoryGroup(label, items) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { history.clearFinished() }
    }

    private fun dayLabel(time: Long): String = when {
        DateUtils.isToday(time) -> "Today"
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateFormat.format("EEEE, d MMMM yyyy", Date(time)).toString()
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    FdScaffold(
        title = "History",
        onBack = onBack,
        actions = { if (!groups.isNullOrEmpty()) TextButton(onClick = viewModel::clear) { Text("Clear") } },
    ) { padding ->
        val list = groups
        if (list != null && list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No transfers yet", style = MaterialTheme.typography.bodyLarge, color = FdColors.TextSecondary)
            }
            return@FdScaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (group in list.orEmpty()) {
                item(key = "h-${group.label}") { SectionLabel(group.label, Modifier.padding(top = 10.dp)) }
                items(group.records, key = { it.sessionId }) { record ->
                    TransferRecordRow(
                        record = record,
                        time = DateFormat.format("HH:mm", Date(record.createdAt)).toString(),
                        onClick = { onOpen(record.sessionId) },
                    )
                }
            }
        }
    }
}

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val history: HistoryRepository,
) : ViewModel() {
    private val sessionId: String = savedState["sessionId"] ?: ""

    val detail: StateFlow<TransferRecordDetail?> =
        history.detail(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            history.delete(sessionId)
            onDeleted()
        }
    }

    /** Puts the files of this outgoing transfer back into the selection so it can be sent again. */
    fun sendAgain(onReady: () -> Unit) {
        viewModelScope.launch { if (history.reselect(sessionId) > 0) onReady() }
    }
}

@Composable
fun HistoryDetailScreen(onBack: () -> Unit, onSendAgain: () -> Unit, viewModel: HistoryDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val record = detail?.record
    FdScaffold(
        title = "Transfer details",
        onBack = onBack,
        actions = {
            if (record != null && record.status.isTerminal) {
                IconButton(onClick = { viewModel.delete(onBack) }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete from history")
                }
            }
        },
    ) { padding ->
        if (record == null) return@FdScaffold
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    FdCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (record.direction == TransferDirection.SEND) "Sent to " else "Received from ") + record.peerName,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    DateFormat.format("d MMM yyyy, HH:mm", Date(record.createdAt)).toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FdColors.TextSecondary,
                                )
                            }
                            StatusPill(statusLabel(record.status), statusColor(record.status))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row {
                            Info("Files", record.totalFiles.toString(), Modifier.weight(1f))
                            Info("Size", Formatters.bytes(record.totalBytes), Modifier.weight(1f))
                            Info("Speed", if (record.averageSpeed > 0) Formatters.speed(record.averageSpeed) else "—", Modifier.weight(1f))
                        }
                        record.message?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                        }
                    }
                }
                item { SectionLabel("Files", Modifier.padding(top = 4.dp)) }
                items(detail?.files.orEmpty().filter { !it.isDirectory }, key = { it.fileId }) { file -> FileProgressRow(file) }
            }
            if (record.direction == TransferDirection.SEND && record.status != TransferStatus.Completed && record.status.isTerminal) {
                SecondaryButton(
                    "Send again",
                    { viewModel.sendAgain(onSendAgain) },
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    icon = Icons.Rounded.Refresh,
                )
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionLabel(label)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}
