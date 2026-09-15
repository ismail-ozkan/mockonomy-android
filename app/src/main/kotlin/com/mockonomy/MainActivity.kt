package com.mockonomy

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mockonomy.autostart.AutostartHandler
import com.mockonomy.data.AppearanceSettingsStore
import com.mockonomy.data.ThemeMode
import com.mockonomy.ui.navigation.MockonomyNavHost
import com.mockonomy.ui.theme.MockonomyTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app's only Activity — every screen lives inside [MockonomyNavHost]. Starting and
 * stopping servers is the job of the environment list screen.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var readiness: AppReadiness

    @Inject
    lateinit var autostart: AutostartHandler

    @Inject
    lateinit var appearance: AppearanceSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // Installs Theme.Mockonomy.Splash from the manifest and then switches to
        // postSplashScreenTheme (Theme.Mockonomy). Must be called BEFORE super.onCreate.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        holdSplashUntilReady(splashScreen)
        fadeSplashOut(splashScreen)
        setContent {
            // Persistent theme preference. Until the value is read the DARK default is used —
            // the same as the store's own default, which avoids a brief flash to light/system.
            val mode by appearance.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Edge-to-edge + theme-aware system bar icons. The status bar becomes transparent
            // and the app's `Surface` background flows behind it — in dark mode #161826
            // continues and icons stay light (even if the system is in light mode).
            // SideEffect: re-applied when the theme preference changes (so the
            // System → Light → Dark segment works live).
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    },
                )
            }
            MockonomyTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MockonomyTheme.colors.background,
                ) {
                    // Edge-to-edge removed the system bar backgrounds; now apply the
                    // safeDrawing insets in one place so content does not slide under the
                    // status/nav bars. The environment list Scaffold resets its own insets
                    // (otherwise the padding would be doubled).
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        RequestNotificationPermission()
                        MockonomyNavHost()
                    }
                }
            }
        }
        handleAutostart(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAutostart(intent)
    }

    /**
     * When started with `am start -n com.mockonomy/.MainActivity --es env <key>`, activates
     * the given environment and starts the server — for CI and ADB automation. Since the
     * Activity is in the foreground, `startForegroundService` is not hit by the Android 12+
     * restriction. Does nothing without the extra.
     */
    private fun handleAutostart(intent: Intent?) {
        val envKey = intent?.getStringExtra(EXTRA_ENV) ?: return
        lifecycleScope.launch { autostart.handle(envKey) }
    }

    /**
     * The splash screen stays **until data is ready**, not just until the first frame (see
     * [AppReadiness]). It is not an artificial delay: the duration depends on real work,
     * and the screen goes away as soon as the work is done.
     *
     * Why there is an upper bound: if the store never answered for some reason, the
     * condition would never clear and the app would look frozen. A launch that hits the
     * bound is drawn with an empty list — better than a stuck splash screen.
     */
    private fun holdSplashUntilReady(splashScreen: SplashScreen) {
        val deadline = SystemClock.uptimeMillis() + MAX_SPLASH_HOLD_MS
        splashScreen.setKeepOnScreenCondition {
            !readiness.isReady && SystemClock.uptimeMillis() < deadline
        }
    }

    /** The splash screen leaves with a short fade instead of being cut off. */
    private fun fadeSplashOut(splashScreen: SplashScreen) {
        splashScreen.setOnExitAnimationListener { provider ->
            provider.view.animate()
                .alpha(0f)
                .scaleX(SPLASH_EXIT_SCALE)
                .scaleY(SPLASH_EXIT_SCALE)
                .setDuration(SPLASH_EXIT_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction(provider::remove)
                .start()
        }
    }

    private companion object {
        const val MAX_SPLASH_HOLD_MS = 2_000L
        const val SPLASH_EXIT_MS = 250L
        const val SPLASH_EXIT_SCALE = 0.92f
        const val EXTRA_ENV = "env"
    }
}

/**
 * The service works without the notification permission, but the user could not stop the
 * server from the notification. It is requested once at launch, app-wide — not tied to a
 * particular screen.
 */
@Composable
private fun RequestNotificationPermission() {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* if denied the service still works; only the notification is not shown */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
