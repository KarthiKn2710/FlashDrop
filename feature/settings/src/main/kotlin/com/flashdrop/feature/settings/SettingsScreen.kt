package com.flashdrop.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.model.AppSettings
import com.flashdrop.core.model.Diagnostics
import com.flashdrop.core.model.TransportType
import com.flashdrop.core.model.TrustedDeviceInfo
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TransferController
import com.flashdrop.domain.repository.TrustedDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val trusted: List<TrustedDeviceInfo> = emptyList(),
    val diagnostics: Diagnostics? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val trustedDevices: TrustedDeviceRepository,
    private val controller: TransferController,
) : ViewModel() {
    private val diagnostics = MutableStateFlow<Diagnostics?>(null)

    val state: StateFlow<SettingsUiState> =
        combine(repository.settings, trustedDevices.devices(), diagnostics, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        viewModelScope.launch { diagnostics.value = repository.diagnostics() }
    }

    fun setDeviceName(name: String) = update { repository.setDeviceName(name) }
    fun setNotifications(value: Boolean) = update { repository.setNotificationsEnabled(value) }
    fun setAutoAccept(value: Boolean) = update { repository.setAutoAcceptTrusted(value) }
    fun setSaveLocation(treeUri: String?) = update { repository.setSaveLocation(treeUri) }
    fun removeTrusted(deviceId: String) = update { trustedDevices.remove(deviceId) }

    fun setStayVisible(value: Boolean) = update {
        repository.setStayVisible(value)
        if (value) controller.startReceiving()
        else if (controller.session.value?.status?.isTerminal != false) controller.stopReceiving()
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    var editingName by rememberSaveable { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setSaveLocation(uri.toString())
    }

    FdScaffold(title = "Settings", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                SectionLabel("This device", Modifier.padding(vertical = 8.dp))
                FdCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    ValueRow("Device name", settings.deviceName, "Shown to nearby devices") { editingName = true }
                    Divider()
                    SwitchRow(
                        "Stay visible",
                        "Keep receiving after you leave the Receive screen. A notification stays on while visible.",
                        settings.stayVisible,
                        viewModel::setStayVisible,
                    )
                }
            }
            item {
                SectionLabel("Receiving", Modifier.padding(top = 20.dp, bottom = 8.dp))
                FdCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    ValueRow("Save location", settings.saveLabel, "Tap to choose another folder") { pickFolder.launch(null) }
                    if (settings.saveTreeUri != null) {
                        Divider()
                        ValueRow("Use default location", AppSettings.DEFAULT_SAVE_LABEL, null) { viewModel.setSaveLocation(null) }
                    }
                    Divider()
                    SwitchRow(
                        "Auto-accept from trusted devices",
                        "Skip the approval step for devices you marked as trusted.",
                        settings.autoAcceptTrusted,
                        viewModel::setAutoAccept,
                    )
                    Divider()
                    SwitchRow(
                        "Notifications",
                        "Alerts for incoming requests and finished transfers.",
                        settings.notificationsEnabled,
                        viewModel::setNotifications,
                    )
                }
            }
            item { SectionLabel("Trusted devices", Modifier.padding(top = 20.dp, bottom = 8.dp)) }
            if (state.trusted.isEmpty()) {
                item {
                    FdCard(Modifier.fillMaxWidth()) {
                        Text(
                            "No trusted devices. You can trust a device while confirming its pairing code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FdColors.TextSecondary,
                        )
                    }
                }
            } else {
                items(state.trusted, key = { it.deviceId }) { device ->
                    FdCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentPadding = PaddingValues(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(device.displayName, style = MaterialTheme.typography.titleSmall)
                                Text("Key ${device.fingerprint}", style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
                            }
                            IconButton(onClick = { viewModel.removeTrusted(device.deviceId) }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Remove ${device.displayName}", tint = FdColors.TextSecondary)
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("Diagnostics", Modifier.padding(top = 20.dp, bottom = 8.dp))
                FdCard(Modifier.fillMaxWidth()) {
                    val d = state.diagnostics
                    DiagnosticLine("Protocol", d?.protocolVersion ?: "…")
                    DiagnosticLine("Device key", d?.deviceFingerprint ?: "…")
                    for (t in d?.transports.orEmpty()) {
                        DiagnosticLine(
                            transportName(t.type),
                            when {
                                !t.implemented -> "Not in this build"
                                t.available -> "Available"
                                else -> "Unavailable"
                            },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (editingName) {
        var name by rememberSaveable { mutableStateOf(settings.deviceName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            containerColor = FdColors.Elevated,
            title = { Text("Device name") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    viewModel.setDeviceName(name)
                    editingName = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }
}

private fun transportName(type: TransportType) = when (type) {
    TransportType.LAN -> "Wi-Fi / LAN"
    TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
    TransportType.WIFI_AWARE -> "Wi-Fi Aware"
    TransportType.NEARBY -> "Nearby Connections"
}

@Composable
private fun Divider() = HorizontalDivider(color = FdColors.Border)

@Composable
private fun ValueRow(title: String, value: String, hint: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.titleSmall)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = FdColors.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FdColors.Accent,
                checkedThumbColor = FdColors.OnAccent,
                uncheckedTrackColor = FdColors.Elevated,
                uncheckedBorderColor = FdColors.Border,
            ),
        )
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = FdColors.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
