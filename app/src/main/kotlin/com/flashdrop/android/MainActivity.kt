package com.flashdrop.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.flashdrop.core.designsystem.FdColors
import com.flashdrop.core.designsystem.FlashDropTheme
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = dark, navigationBarStyle = dark)
        setContent {
            FlashDropTheme {
                Surface(Modifier.fillMaxSize(), color = FdColors.Background) { FlashDropNavHost() }
            }
        }
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
fun FlashDropNavHost(appViewModel: AppViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val session by appViewModel.session.collectAsStateWithLifecycle()

    // A pairing code or an incoming request needs the user wherever they are in the app.
    val needsAttention = session?.pairing != null || session?.incoming != null
    LaunchedEffect(needsAttention) { if (needsAttention) nav.openTransfer() }

    NavHost(navController = nav, startDestination = Routes.HOME) {
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
