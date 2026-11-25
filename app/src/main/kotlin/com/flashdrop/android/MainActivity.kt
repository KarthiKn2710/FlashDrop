package com.flashdrop.android

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FdMotion
import com.flashdrop.core.designsystem.FlashDropTheme
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.repository.TransferController
import com.flashdrop.feature.history.HistoryDetailScreen
import com.flashdrop.feature.history.HistoryScreen
import com.flashdrop.feature.home.HomeScreen
import com.flashdrop.feature.send.DiscoveryScreen
import com.flashdrop.feature.send.SelectionScreen
import com.flashdrop.feature.settings.SettingsScreen
import com.flashdrop.feature.transfer.ReceiveScreen
import com.flashdrop.feature.transfer.TransferScreen
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var selection: SelectionRepository

    /** Bumped each time files arrive from another app's Share menu; the nav host opens Send. */
    private var shareRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        // Only a fresh launch carries a new share: a recreated activity has already handled it,
        // and reopening from Recents replays the old intent.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleShare(intent)
        setContent {
            FlashDropTheme {
                Surface(Modifier.fillMaxSize(), color = FdColors.Background) { FlashDropNavHost(shareRequest) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null || !SharedFiles.isShare(intent)) return
        val uris = SharedFiles.uris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "FlashDrop can send files, not text or links", Toast.LENGTH_LONG).show()
            return
        }
        // A share is a fresh choice of what to send, so it replaces anything picked earlier.
        selection.clear()
        selection.addFiles(uris.map { it.toString() })
        shareRequest++
    }
}

object Routes {
    const val HOME = "home"
    const val SEND = "send"
    const val DISCOVERY = "discovery"
    const val RECEIVE = "receive"
    const val TRANSFER = "transfer"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{sessionId}"
    const val SETTINGS = "settings"
}

@HiltViewModel
class AppViewModel @Inject constructor(controller: TransferController) : ViewModel() {
    val session = controller.session
}

private fun NavHostController.openTransfer() {
    if (currentDestination?.route == Routes.TRANSFER) return
    navigate(Routes.TRANSFER) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

/**
 * Home, then Send: file picker, discovery, pairing, transfer, completion.
 * Home, then Receive: incoming request, pairing and approval, transfer.
 */
@Composable
fun FlashDropNavHost(shareRequest: Int = 0, appViewModel: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val session by appViewModel.session.collectAsStateWithLifecycle()

    // Files shared from another app: show them on the selection screen, on top of Home.
    LaunchedEffect(shareRequest) {
        if (shareRequest == 0) return@LaunchedEffect
        nav.navigate(Routes.SEND) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }

    // A pairing code or an incoming request needs the user wherever they are in the app.
    val needsAttention = session?.pairing != null || session?.incoming != null
    LaunchedEffect(needsAttention) { if (needsAttention) nav.openTransfer() }

    // Forward: the new screen slides in a little from the right while fading in; back: reversed.
    // Short distances and durations keep it calm rather than showy.
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = {
            slideInHorizontally(tween(FdMotion.MEDIUM, easing = FdMotion.Enter)) { it / 8 } +
                fadeIn(tween(FdMotion.MEDIUM, easing = FdMotion.Enter))
        },
        exitTransition = {
            slideOutHorizontally(tween(FdMotion.MEDIUM, easing = FdMotion.Enter)) { -it / 16 } +
                fadeOut(tween(FdMotion.SHORT))
        },
        popEnterTransition = {
            slideInHorizontally(tween(FdMotion.MEDIUM, easing = FdMotion.Enter)) { -it / 16 } +
                fadeIn(tween(FdMotion.MEDIUM, easing = FdMotion.Enter))
        },
        popExitTransition = {
            slideOutHorizontally(tween(FdMotion.MEDIUM, easing = FdMotion.Enter)) { it / 8 } +
                fadeOut(tween(FdMotion.SHORT))
        },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onSend = { nav.navigate(Routes.SEND) },
                onReceive = { nav.navigate(Routes.RECEIVE) },
                onOpenTransfer = { nav.openTransfer() },
                onHistory = { nav.navigate(Routes.HISTORY) },
                onOpenRecord = { nav.navigate("history/$it") },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEND) {
            SelectionScreen(onBack = { nav.popBackStack() }, onContinue = { nav.navigate(Routes.DISCOVERY) })
        }
        composable(Routes.DISCOVERY) {
            DiscoveryScreen(onBack = { nav.popBackStack() }, onStarted = { nav.openTransfer() })
        }
        composable(Routes.RECEIVE) {
            ReceiveScreen(onBack = { nav.popBackStack() }, onSession = { nav.openTransfer() })
        }
        composable(Routes.TRANSFER) {
            TransferScreen(
                onBack = { nav.popBackStack() },
                onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("history/$it") })
        }
        composable(Routes.HISTORY_DETAIL) {
            HistoryDetailScreen(onBack = { nav.popBackStack() }, onSendAgain = { nav.navigate(Routes.DISCOVERY) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
