package com.syconx.us2us.app

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.syconx.us2us.BuildConfig
import com.syconx.us2us.app.debug.LaunchArgs
import com.syconx.us2us.domain.nav.LaunchConfig
import com.syconx.us2us.feature.chats.InviteLinkInbox
import com.syconx.us2us.ui.security.PrivacySnapshot

/**
 * The app's only activity: edge to edge, never captured, and the scene lifecycle forwarded to the
 * [AppModel] (pause → the 3.4 cover, stop → the lock is due, start → the lock, resume → the cover
 * dissolves). A recreation for a theme or language change is not a pause or a stop.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var model: AppModel
    private var barsDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as Us2usApplication
        model = app.appModel(LaunchArgs.read(intent))
        super.onCreate(savedInstanceState)
        // The per-app language reaches the system only through a live AppCompat delegate: a
        // `U2Language` written while the model was made (before this activity's delegate existed)
        // is applied now, and the activity comes back in that language.
        app.services.preferences.applyLanguage()
        applySystemBars(dark = true)
        applyCapturePolicy(app.launchConfig)
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (isChangingConfigurations) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> model.sceneWillResignActive { PrivacySnapshot.capture(window.decorView) }
                    Lifecycle.Event.ON_STOP -> model.sceneDidEnterBackground()
                    Lifecycle.Event.ON_START -> model.sceneWillEnterForeground()
                    Lifecycle.Event.ON_RESUME -> model.sceneDidBecomeActive()
                    else -> Unit
                }
            },
        )
        if (savedInstanceState == null) {
            // Invite links (https://us2us.app/u/<username>) and the debug `U2OpenURL`; 4.1 opens them after the unlock.
            intent?.dataString?.let(InviteLinkInbox::handle)
            app.launchConfig.openUrl?.let(InviteLinkInbox::handle)
        }
        setContent {
            AppRoot(model, services = app.services, onSystemBarsDark = ::applySystemBars)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let(InviteLinkInbox::handle)
    }

    /**
     * Edge to edge with transparent bars; the icons are light on the dark theme (and on screens that
     * are always dark) and dark on the light theme.
     */
    private fun applySystemBars(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        val style = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
    }

    /**
     * The screen is never captured (screenshots, recordings, the recents thumbnail), as on iOS.
     * Only a debug build launched with `U2AllowCapture` lifts it, for screenshot runs.
     */
    private fun applyCapturePolicy(config: LaunchConfig) {
        val allowCapture = BuildConfig.DEBUG && config.allowCapture
        if (allowCapture) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(allowCapture)
    }
}
