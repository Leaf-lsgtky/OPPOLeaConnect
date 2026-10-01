package com.github.leaf.leaconnect.ui

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.ui.NavDisplay
import androidx.navigation3.ui.NavDisplayTransitionEffects
import com.github.leaf.leaconnect.ui.data.ConfigStore
import com.github.leaf.leaconnect.ui.screen.AdvancedScreen
import com.github.leaf.leaconnect.ui.screen.HomeScreen
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

sealed interface Route : NavKey {
    data object Home : Route
    data object Advanced : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                App(store)
            }
        }
    }
}

@Composable
private fun App(store: ConfigStore) {
    val backStack: SnapshotStateList<Route> = remember { listOf(Route.Home).toMutableStateList() }
    val entryProvider = remember(backStack) {
        entryProvider<Route> {
            entry(Route.Home) {
                HomeScreen(store = store, onAdvanced = { backStack += Route.Advanced })
            }
            entry(Route.Advanced) {
                AdvancedScreen(store = store, onBack = { backStack.removeLastOrNull() })
            }
        }
    }
    val entries = rememberDecoratedNavEntries(backStack = backStack, entryProvider = entryProvider)
    // 页面切换交给 miuix 那份 NavDisplay：圆角裁切 + 压暗 + 跟手的预测式返回，
    // 和 HyperOS 自己的设置页一致，不用自己凑动画
    NavDisplay(
        entries = entries,
        onBack = { backStack.removeLastOrNull() },
        transitionEffects = NavDisplayTransitionEffects(),
    )
}
