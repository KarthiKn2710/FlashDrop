package com.flashdrop.feature.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import android.widget.Toast
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.IconBadge
import com.flashdrop.core.designsystem.TransferPermissionsEffect
import com.flashdrop.core.designsystem.transportLabel
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.domain.repository.DiscoveryRepository
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.usecase.SendSelectionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DiscoveryUiState(
    val devices: List<RemoteDevice> = emptyList(),
    val networkAvailable: Boolean = true,
    val itemCount: Int = 0,
    val totalBytes: Long = 0,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val discovery: DiscoveryRepository,
    selection: SelectionRepository,
    private val sendSelection: SendSelectionUseCase,
) : ViewModel() {
    private val generation = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DiscoveryUiState> =
        combine(generation.flatMapLatest { discovery.devices() }, selection.items) { devices, items ->
            DiscoveryUiState(devices, discovery.isAnyTransportAvailable(), items.size, items.sumOf { it.size })
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

    TransferPermissionsEffect(onResult = viewModel::refresh)

    FdScaffold(title = "Choose a device", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Sending ${state.itemCount} ${if (state.itemCount == 1) "item" else "items"} · ${Formatters.bytes(state.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FdColors.TextSecondary,
                )
            }

            if (!state.networkAvailable) {
                item {
                    FdCard(Modifier.fillMaxWidth()) {
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
                item {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SectionLabel("Nearby devices", Modifier.weight(1f))
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = FdColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                    }
                }
                if (state.devices.isEmpty()) {
                    item {
                        FdCard(Modifier.fillMaxWidth()) {
                            Text("Looking for devices…", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "On the other device, open FlashDrop and tap Receive. Devices on the same Wi-Fi are found fastest; without a router, Wi-Fi Direct is used.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FdColors.TextSecondary,
                            )
                        }
                    }
                }
                items(state.devices, key = { it.id }) { device ->
                    FdCard(Modifier.fillMaxWidth(), onClick = {
                        if (viewModel.send(device)) onStarted()
                        else Toast.makeText(context, "Another transfer is still running", Toast.LENGTH_SHORT).show()
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.PhoneAndroid)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (device.transport == com.flashdrop.core.model.TransportType.LAN) "Wi-Fi · ${device.host}" else transportLabel(device.transport) + " · no router needed",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FdColors.TextSecondary,
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = FdColors.TextSecondary)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                SecondaryButton("Enter address manually", { manualOpen = true }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Lan)
            }
        }
    }

    if (manualOpen) {
        ManualAddressDialog(
            onDismiss = { manualOpen = false },
            onConnect = { address ->
                val error = viewModel.sendTo(address)
                if (error == null) {
                    manualOpen = false
                    onStarted()
                }
                error
            },
        )
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
