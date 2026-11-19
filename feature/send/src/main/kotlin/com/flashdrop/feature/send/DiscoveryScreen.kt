package com.flashdrop.feature.send

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.DeviceAvatar
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdMotion
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.Haptic
import com.flashdrop.core.designsystem.IconBadge
import com.flashdrop.core.designsystem.OnChange
import com.flashdrop.core.designsystem.ScanningRadar
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.TransferPermissionsEffect
import com.flashdrop.core.designsystem.rememberHaptics
import com.flashdrop.core.designsystem.transportLabel
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.core.model.TransportType
import com.flashdrop.domain.repository.DiscoveryRepository
import com.flashdrop.domain.repository.HistoryRepository
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.usecase.SendSelectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** A nearby device, plus when files were last sent to a device announcing the same name. */
data class NearbyDevice(val device: RemoteDevice, val lastSentAt: Long?)

data class DiscoveryUiState(
    val devices: List<NearbyDevice> = emptyList(),
    val networkAvailable: Boolean = true,
    val itemCount: Int = 0,
    val totalBytes: Long = 0,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val discovery: DiscoveryRepository,
    selection: SelectionRepository,
    history: HistoryRepository,
    private val sendSelection: SendSelectionUseCase,
) : ViewModel() {
    private val generation = MutableStateFlow(0)

    /** Most recent successful send per peer name. */
    private val lastSent = history.records().map { records ->
        records.asSequence()
            .filter { it.direction == TransferDirection.SEND && it.status == TransferStatus.Completed }
            .groupBy { it.peerName }
            .mapValues { (_, list) -> list.maxOf { it.createdAt } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DiscoveryUiState> =
        combine(generation.flatMapLatest { discovery.devices() }, selection.items, lastSent) { devices, items, sent ->
            // Devices used before come first; otherwise keep the order they were found in.
            val nearby = devices.map { NearbyDevice(it, sent[it.displayName]) }
                .sortedByDescending { it.lastSentAt ?: Long.MIN_VALUE }
            DiscoveryUiState(nearby, discovery.isAnyTransportAvailable(), items.size, items.sumOf { it.size })
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(2_000),
            DiscoveryUiState(networkAvailable = discovery.isAnyTransportAvailable()),
        )

    /** Restarts discovery, e.g. after a permission grant made another transport usable. */
    fun refresh() {
        generation.value++
    }

    fun send(device: RemoteDevice): Boolean = sendSelection(device)

    /** Parses "192.168.1.20:41234" and starts sending. Returns an error message, or null on success. */
    fun sendTo(address: String): String? {
        val host = address.substringBeforeLast(':', "").trim()
        val port = address.substringAfterLast(':', "").trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return "Enter the address as shown on the other device, e.g. 192.168.1.20:41234"
        return if (sendSelection(discovery.manualDevice(host, port))) null else "Another transfer is already running"
    }
}

@Composable
fun DiscoveryScreen(onBack: () -> Unit, onStarted: () -> Unit, viewModel: DiscoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = rememberHaptics()

    TransferPermissionsEffect(onResult = viewModel::refresh)
    // A light tick whenever another device shows up.
    OnChange(state.devices.size) { count -> if (count > 0) haptics.perform(Haptic.Tick) }

    fun start(device: RemoteDevice) {
        if (viewModel.send(device)) {
            haptics.perform(Haptic.Confirm)
            onStarted()
        } else {
            haptics.perform(Haptic.Error)
            Toast.makeText(context, "Another transfer is still running", Toast.LENGTH_SHORT).show()
        }
    }

    FdScaffold(title = "Choose a device", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                ScanHeader(state, Modifier.animateItem())
            }

            if (!state.networkAvailable) {
                item(key = "wifi-off") {
                    FdCard(Modifier.fillMaxWidth().animateItem()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.WifiOff, tint = FdColors.Warning)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Wi-Fi is off", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Turn on Wi-Fi on both devices. A shared router is not required.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FdColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            } else {
                if (state.devices.isNotEmpty()) {
                    item(key = "label") { SectionLabel("Nearby devices", Modifier.padding(top = 4.dp).animateItem()) }
                }
                items(state.devices, key = { it.device.id }) { nearby ->
                    DeviceRow(nearby, Modifier.animateItem()) { start(nearby.device) }
                }
            }

            item(key = "manual") {
                Column(Modifier.animateItem()) {
                    Spacer(Modifier.height(4.dp))
                    SecondaryButton("Enter address manually", { manualOpen = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Lan)
                }
            }
        }
    }

    if (manualOpen) {
        ManualAddressDialog(
            onDismiss = { manualOpen = false },
            onConnect = { address ->
                val error = viewModel.sendTo(address)
                if (error == null) {
                    haptics.perform(Haptic.Confirm)
                    manualOpen = false
                    onStarted()
                } else {
                    haptics.perform(Haptic.Reject)
                }
                error
            },
        )
    }
}

@Composable
private fun ScanHeader(state: DiscoveryUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScanningRadar(
            active = state.networkAvailable,
            icon = Icons.Rounded.Radar,
            size = 148.dp,
            coreSize = 72.dp,
        )
        Spacer(Modifier.height(12.dp))
        val count = state.devices.size
        AnimatedContent(
            targetState = when {
                !state.networkAvailable -> "Not searching"
                count == 0 -> "Looking for nearby devices…"
                count == 1 -> "1 device nearby"
                else -> "$count devices nearby"
            },
            transitionSpec = { fadeIn(tween(FdMotion.MEDIUM)) togetherWith fadeOut(tween(FdMotion.SHORT)) },
            label = "scanTitle",
        ) { title ->
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (count == 0 && state.networkAvailable) {
                "On the other device, open FlashDrop and tap Receive."
            } else {
                "Sending ${state.itemCount} ${if (state.itemCount == 1) "item" else "items"} · ${Formatters.bytes(state.totalBytes)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = FdColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DeviceRow(nearby: NearbyDevice, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val device = nearby.device
    val route = if (device.transport == TransportType.LAN) "Wi-Fi" else transportLabel(device.transport) + " · no router needed"
    val history = nearby.lastSentAt?.let {
        "Sent to before · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    }
    // No press haptic here: starting the send plays its own confirm (or error) feedback.
    FdCard(Modifier.fillMaxWidth().then(modifier), onClick = onClick, onClickLabel = "Send to ${device.displayName}", haptic = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.displayName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (history != null) "$route · $history" else route,
                    style = MaterialTheme.typography.bodySmall,
                    color = FdColors.TextSecondary,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = FdColors.TextSecondary)
        }
    }
}

@Composable
private fun ManualAddressDialog(onDismiss: () -> Unit, onConnect: (String) -> String?) {
    var address by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = FdColors.Elevated,
        title = { Text("Connect by address") },
        text = {
            Column {
                Text(
                    "The address is shown on the Receive screen of the other device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FdColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        error = null
                    },
                    singleLine = true,
                    label = { Text("Address") },
                    placeholder = { Text("192.168.1.20:41234") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { TextButton(onClick = { error = onConnect(address) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
