package com.flashdrop.core.designsystem

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FdScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = FdColors.Background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        val haptics = rememberHaptics()
                        IconButton(onClick = {
                            haptics.perform(Haptic.Tap)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = FdColors.Background,
                    titleContentColor = FdColors.TextPrimary,
                    navigationIconContentColor = FdColors.TextPrimary,
                    actionIconContentColor = FdColors.TextSecondary,
                ),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * Compact card with a subtle border; the basic surface of every screen. A clickable card gives
 * a light tap, shrinks a little while pressed, and reads as one button to screen readers.
 */
@Composable
fun FdCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    /** Feedback on press; null when the click handler plays its own (e.g. a confirm). */
    haptic: Haptic? = Haptic.Tap,
    color: Color = FdColors.Surface,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics(mergeDescendants = true) {}
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                            role = Role.Button,
                            onClickLabel = onClickLabel,
                        ) {
                            haptic?.let(haptics::perform)
                            onClick()
                        }
                } else {
                    Modifier
                },
            ),
        color = color,
        shape = shape,
        border = BorderStroke(1.dp, FdColors.Border),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    haptic: Haptic = Haptic.Tap,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    Button(
        onClick = {
            haptics.perform(haptic)
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.heightIn(min = 52.dp).pressScale(interaction),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = FdColors.Accent,
            contentColor = FdColors.OnAccent,
            disabledContainerColor = FdColors.Elevated,
            disabledContentColor = FdColors.TextSecondary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = FdColors.TextPrimary,
    haptic: Haptic = Haptic.Tap,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    OutlinedButton(
        onClick = {
            haptics.perform(haptic)
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.heightIn(min = 52.dp).pressScale(interaction),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, FdColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        color = FdColors.TextSecondary,
    )
}

/**
 * A small coloured status label. Colour changes are animated; with [announce], screen readers
 * read out each new status (use it only where the status changes rarely).
 */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, announce: Boolean = false) {
    val animated by animateColorAsState(color, tween(FdMotion.MEDIUM), label = "pillColor")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(animated.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(animated))
        Text(text, style = MaterialTheme.typography.labelSmall, color = animated)
    }
}

/**
 * Progress arrives a few times a second; the bar glides between updates instead of jumping.
 * [label] names it for screen readers ("Overall progress").
 */
@Composable
fun FdProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = FdColors.Accent, label: String? = null) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(220, easing = LinearEasing), label = "progress")
    val tint by animateColorAsState(color, tween(FdMotion.MEDIUM), label = "progressColor")
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        color = tint,
        trackColor = FdColors.Elevated,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/**
 * A device's initial on a colour picked from its name, so the same device always looks the same.
 * The colour carries no meaning (the name is unauthenticated); it only helps tell devices apart.
 */
@Composable
fun DeviceAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val color = AVATAR_COLORS[(name.hashCode() and Int.MAX_VALUE) % AVATAR_COLORS.size]
    val initial = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

private val AVATAR_COLORS = listOf(
    Color(0xFFA78BFA), Color(0xFF5EEAD4), Color(0xFFFBBF24),
    Color(0xFFF9A8D4), Color(0xFF7DD3FC), Color(0xFFBEF264),
)

/** Round tinted container for a leading icon. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = FdColors.Accent, size: Int = 40) {
    Box(
        modifier = modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.5f).dp))
    }
}

/**
 * Asks for the notification permission at the point of use (starting a send or receive), never at
 * first launch. Transfers work without it; only the progress notification is hidden.
 */
@Composable
fun TransferPermissionsEffect(onResult: () -> Unit = {}) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    LaunchedEffect(Unit) {
        // Wi-Fi Direct (the no-router path) needs "nearby devices" on Android 13+, and location
        // on Android 10-12 where the platform offers no narrower permission.
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
}

fun transportLabel(type: com.flashdrop.core.model.TransportType): String = when (type) {
    com.flashdrop.core.model.TransportType.LAN -> "Wi-Fi"
    com.flashdrop.core.model.TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
    com.flashdrop.core.model.TransportType.WIFI_AWARE -> "Wi-Fi Aware"
    com.flashdrop.core.model.TransportType.NEARBY -> "Nearby"
}
