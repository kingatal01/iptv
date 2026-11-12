package com.yamking.iptv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icônes du lecteur absentes du jeu d'icônes de base (tracés Material, grille 24 × 24). */
object PlayerIcons {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")

    val VolumeUp = icon(
        "VolumeUp",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z" +
            "M14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
    )

    val VolumeOff = icon(
        "VolumeOff",
        "M3,9v6h4l5,5V4L7,9H3zM15.3,8.3l-1.4,1.4L16.2,12l-2.3,2.3l1.4,1.4L17.6,13.4l2.3,2.3l1.4,-1.4L19,12l2.3,-2.3" +
            "l-1.4,-1.4L17.6,10.6L15.3,8.3z",
    )

    val Fullscreen = icon(
        "Fullscreen",
        "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z",
    )

    val FullscreenExit = icon(
        "FullscreenExit",
        "M5,16h3v3h2v-5H5v2zM8,8H5v2h5V5H8v3zM14,19h2v-3h3v-2h-5v5zM16,8V5h-2v5h5V8h-3z",
    )
}
