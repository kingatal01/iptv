package com.yamking.iptv

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.yamking.iptv.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Storage.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
        }
    }

    /** L'utilisateur quitte l'app pendant une lecture : la vidéo continue dans une vignette flottante. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlaybackState.playing) {
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlaybackState.inPictureInPicture.value = isInPictureInPictureMode
    }
}