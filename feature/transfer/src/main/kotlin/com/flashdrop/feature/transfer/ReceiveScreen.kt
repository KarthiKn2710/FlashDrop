package com.flashdrop.feature.transfer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.flashdrop.core.designsystem.FdCard
import com.flashdrop.core.designsystem.FdMotion
import com.flashdrop.core.designsystem.Haptic
import com.flashdrop.core.designsystem.HapticOnChange
import com.flashdrop.core.designsystem.ScanningRadar
import com.flashdrop.core.designsystem.rememberHaptics
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdScaffold
import com.flashdrop.core.designsystem.TransferPermissionsEffect
import com.flashdrop.core.designsystem.transportLabel
import com.flashdrop.core.designsystem.SecondaryButton
import com.flashdrop.core.designsystem.SectionLabel
import com.flashdrop.core.designsystem.StatusPill
import com.flashdrop.core.model.ReceiveState
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TransferController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ReceiveViewModel @Inject constructor(
    private val controller: TransferController,
    settings: SettingsRepository,
) : ViewModel() {
    val receiving: StateFlow<ReceiveState> = controller.receiving
    val session: StateFlow<TransferSnapshot?> = controller.session
    val stayVisible: StateFlow<Boolean> = settings.settings.map { it.stayVisible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun start() = controller.startReceiving()

    /** Leaving the screen hides the device again unless the user chose to stay visible. */
    fun leave() {
        val live = controller.session.value?.status?.isTerminal == false
        if (!stayVisible.value && !live) controller.stopReceiving()
    }

    fun stop() = controller.stopReceiving()
}

@Composable
fun ReceiveScreen(onBack: () -> Unit, onSession: () -> Unit, viewModel: ReceiveViewModel = hiltViewModel()) {
    val receiving by viewModel.receiving.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    HapticOnChange(receiving.visible, Haptic.Tick) { it }
    HapticOnChange(receiving.error, Haptic.Error) { it != null }

    TransferPermissionsEffect(onResult = { viewModel.stop(); viewModel.start() })
    LaunchedEffect(Unit) { viewModel.start() }
    LaunchedEffect(session?.sessionId, session?.status?.isTerminal) {
        if (session != null && session?.status?.isTerminal == false) onSession()
    }
    val leave = {
        viewModel.leave()
        onBack()
    }
    BackHandler(onBack = leave)

    FdScaffold(title = "Receive", onBack = leave) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            ScanningRadar(active = receiving.visible, icon = Icons.Rounded.Download, color = FdColors.Success)
            Spacer(Modifier.height(8.dp))

            val phase = when {
                receiving.error != null -> 2
                receiving.visible -> 1
                else -> 0
            }
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(FdMotion.MEDIUM)) togetherWith fadeOut(tween(FdMotion.SHORT)) },
                label = "receiveState",
            ) { current ->
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (current) {
                        2 -> {
                            Text("Can't receive right now", style = MaterialTheme.typography.titleLarge)
                            Text(receiving.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = FdColors.Error, textAlign = TextAlign.Center)
                        }
                        1 -> {
                            Text("Ready to receive", style = MaterialTheme.typography.titleLarge)
                            StatusPill("Visible as ${receiving.deviceName}", FdColors.Success)
                            Text(
                                "On the sending device, open FlashDrop, tap Send and choose this device. You will be asked before anything is saved.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = FdColors.TextSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        else -> Text("Starting…", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            if (receiving.error != null) {
                SecondaryButton("Try again", { viewModel.stop(); viewModel.start() })
            }

            if (receiving.visible && receiving.addresses.isNotEmpty()) {
                FdCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Manual address")
                    Spacer(Modifier.height(6.dp))
                    for (address in receiving.addresses) {
                        Text("$address:${receiving.port}", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Use this if the sender cannot find this device automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FdColors.TextSecondary,
                    )
                }
            }

            if (receiving.visible) {
                SecondaryButton("Stop receiving", { viewModel.stop(); onBack() }, Modifier.fillMaxWidth(), haptic = Haptic.Reject)
            }
        }
    }
}
