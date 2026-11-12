package com.yamking.iptv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yamking.iptv.resources.Res
import com.yamking.iptv.resources.app_icon
import com.yamking.iptv.ui.App
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1180.dp, 760.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "IPTV Player",
        // Icône de la fenêtre et de la barre des tâches : la même que sur Android.
        icon = painterResource(Res.drawable.app_icon),
        state = windowState,
    ) {
        CompositionLocalProvider(LocalAppWindowState provides windowState) {
            App()
        }
    }
}
