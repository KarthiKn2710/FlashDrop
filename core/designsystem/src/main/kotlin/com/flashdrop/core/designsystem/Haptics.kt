package com.flashdrop.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * The few kinds of feedback FlashDrop uses. Everything maps to the platform's own haptic
 * constants, so it is subtle by design and follows the phone's "touch feedback" setting.
 */
enum class Haptic {
    /** Any ordinary button or row press. */
    Tap,

    /** A switch, checkbox or filter chip changed. */
    Toggle,

    /** A deliberate positive decision: accept, codes match, send. */
    Confirm,

    /** A deliberate negative decision: decline, cancel, remove. */
    Reject,

    /** Something appeared that deserves a nudge: a device was found, a request arrived. */
    Tick,

    /** A transfer finished and every file verified. */
    Success,

    /** A transfer failed or was declined. */
    Error,
}

/**
 * App-wide switch for haptics. Provide `false` to silence the app's own feedback; this is the
 * single place a Settings toggle needs to drive.
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

class Haptics internal constructor(private val view: View, private val enabled: Boolean) {
    fun perform(type: Haptic) {
        if (!enabled) return
        view.performHapticFeedback(constantFor(type))
    }

    private fun constantFor(type: Haptic): Int {
        val sdk = Build.VERSION.SDK_INT
        return when (type) {
            Haptic.Tap -> HapticFeedbackConstants.KEYBOARD_TAP
            Haptic.Toggle -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Tick -> HapticFeedbackConstants.CLOCK_TICK
            Haptic.Confirm, Haptic.Success ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.Reject, Haptic.Error ->
                if (sdk >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
        }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { Haptics(view, enabled) }
}

/** Runs [action] each time [value] changes, but not for the value the screen starts with. */
@Composable
fun <T> OnChange(value: T, action: (T) -> Unit) {
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(value) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        action(value)
    }
}

/** Plays [type] each time [value] changes to one for which [condition] holds. */
@Composable
fun <T> HapticOnChange(value: T, type: Haptic, condition: (T) -> Boolean = { true }) {
    val haptics = rememberHaptics()
    OnChange(value) { if (condition(it)) haptics.perform(type) }
}
