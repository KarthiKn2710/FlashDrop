package com.flashdrop.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.flashdrop.core.model.Formatters
import com.flashdrop.core.model.ReceiveState
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TransferController
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private fun needsService(session: TransferSnapshot?, receiving: ReceiveState): Boolean =
    receiving.visible || (session != null && !session.status.isTerminal)

/** Starts the foreground service whenever the device becomes visible or a transfer begins. */
@Singleton
class TransferServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: TransferController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun attach() {
        scope.launch {
            combine(controller.session, controller.receiving, ::needsService).distinctUntilChanged().collect { needed ->
                if (!needed) return@collect
                try {
                    ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
                } catch (e: Exception) {
                    // Not allowed from the background on recent Android; the transfer still runs
                    // while the process lives, just without foreground priority.
                    Log.w("TransferService", "Could not start foreground service", e)
                }
            }
        }
    }
}

/**
 * Keeps an active, user-visible transfer (or receive mode) alive in the background and shows its
 * state in a notification. All transfer logic lives in the session manager; this only reflects it.
 */
@AndroidEntryPoint
class TransferService : Service() {
    @Inject lateinit var controller: TransferController
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var alertShown: String? = null
    private var lastSession: TransferSnapshot? = null
    private var notificationsEnabled = true

    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_STATUS, "Transfer status", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_REQUESTS, "Incoming requests", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> controller.cancel()
            ACTION_STOP_RECEIVING -> controller.stopReceiving()
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_STATUS,
                statusNotification(controller.session.value, controller.receiving.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground rejected", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) observer = scope.launch { observe() }
        return START_NOT_STICKY
    }

    private suspend fun observe() {
        launchSettings()
        combine(controller.session, controller.receiving) { session, receiving -> session to receiving }
            // Byte-level progress changes 5 times a second; the notification only needs whole percents.
            .map { (session, receiving) -> Triple(session, receiving, session?.let { (it.progress * 100).toInt() }) }
            .distinctUntilChanged { a, b ->
                a.third == b.third && a.second == b.second && a.first?.status == b.first?.status &&
                    a.first?.pairing == b.first?.pairing && a.first?.incoming == b.first?.incoming
            }
            .collect { (session, receiving, _) ->
                if (session != null) lastSession = session
                if (!needsService(session, receiving)) {
                    finishUp()
                    return@collect
                }
                notifications.notify(NOTIFICATION_STATUS, statusNotification(session, receiving))
                updateLocks(session != null && !session.status.isTerminal)
                updateAlert(session)
            }
    }

    private fun launchSettings() {
        scope.launch { settings.settings.collect { notificationsEnabled = it.notificationsEnabled } }
    }

    private fun finishUp() {
        updateLocks(false)
        notifications.cancel(NOTIFICATION_ALERT)
        alertShown = null
        lastSession?.let { postResult(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- notifications -----------------------------------------------------------------------

    private fun openApp(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, TransferService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun statusNotification(session: TransferSnapshot?, receiving: ReceiveState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_flashdrop)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (session == null || session.status.isTerminal) {
            return builder
                .setContentTitle("Ready to receive")
                .setContentText("Visible to nearby devices as ${receiving.deviceName.ifBlank { "this device" }}")
                .addAction(0, "Stop", action(ACTION_STOP_RECEIVING, 2))
                .build()
        }

        val verb = if (session.direction == TransferDirection.SEND) "Sending to" else "Receiving from"
        val percent = (session.progress * 100).toInt()
        val text = when (session.status) {
            TransferStatus.Transferring ->
                "$percent% · ${Formatters.bytes(session.transferredBytes)} of ${Formatters.bytes(session.totalBytes)}"
            TransferStatus.Paused -> "Paused · $percent%"
            TransferStatus.Interrupted, TransferStatus.Reconnecting -> "Connection lost · reconnecting"
            TransferStatus.Verifying -> "Verifying files"
            TransferStatus.AwaitingApproval -> "Waiting for approval"
            else -> "Connecting"
        }
        val showsProgress = session.status in PROGRESS_STATES
        return builder
            .setContentTitle("$verb ${session.peerName}")
            .setContentText(text)
            .setProgress(if (showsProgress) 100 else 0, percent, false)
            .addAction(0, "Cancel", action(ACTION_CANCEL, 1))
            .build()
    }

    /** Pairing codes and incoming requests need the user: raise a heads-up notification once. */
    private fun updateAlert(session: TransferSnapshot?) {
        val pairing = session?.pairing
        val incoming = session?.incoming
        val id = session?.sessionId.orEmpty()
        val key = when {
            pairing != null -> "pair:$id:${pairing.code}"
            incoming != null -> "incoming:$id"
            else -> null
        }
        if (key == alertShown) return
        alertShown = key
        if (key == null || !notificationsEnabled) {
            notifications.cancel(NOTIFICATION_ALERT)
            return
        }
        val (title, text) = if (pairing != null) {
            "Pair with ${pairing.peerName}?" to "Check that both devices show the code ${pairing.code}"
        } else {
            "${incoming!!.peerName} wants to send files" to
                "${incoming.fileCount} ${if (incoming.fileCount == 1) "file" else "files"} · ${Formatters.bytes(incoming.totalBytes)}"
        }
        notifications.notify(
            NOTIFICATION_ALERT,
            NotificationCompat.Builder(this, CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_stat_flashdrop)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build(),
        )
    }

    private fun postResult(session: TransferSnapshot) {
        if (!notificationsEnabled) return
        val title = when (session.status) {
            TransferStatus.Completed -> if (session.failedFiles == 0) "Transfer complete" else "Transfer finished with errors"
            TransferStatus.Failed -> "Transfer failed"
            else -> return
        }
        val direction = if (session.direction == TransferDirection.SEND) "Sent to" else "Received from"
        notifications.notify(
            NOTIFICATION_RESULT,
            NotificationCompat.Builder(this, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_flashdrop)
                .setContentTitle(title)
                .setContentText(session.message ?: "$direction ${session.peerName} · ${Formatters.bytes(session.totalBytes)}")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        lastSession = null
    }

    // ---- locks -------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun updateLocks(hold: Boolean) {
        if (hold) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flashdrop:transfer").apply {
                        setReferenceCounted(false)
                        acquire(MAX_LOCK_MS)
                    }
            }
            if (wifiLock == null) {
                wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "flashdrop:transfer")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }
    }

    override fun onDestroy() {
        updateLocks(false)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "TransferService"
        const val CHANNEL_STATUS = "transfer_status"
        const val CHANNEL_REQUESTS = "transfer_requests"
        const val NOTIFICATION_STATUS = 1
        const val NOTIFICATION_ALERT = 2
        const val NOTIFICATION_RESULT = 3
        const val ACTION_CANCEL = "com.flashdrop.action.CANCEL"
        const val ACTION_STOP_RECEIVING = "com.flashdrop.action.STOP_RECEIVING"
        const val MAX_LOCK_MS = 6L * 60 * 60 * 1000
        val PROGRESS_STATES = setOf(TransferStatus.Transferring, TransferStatus.Paused, TransferStatus.Verifying)
    }
}
