package dev.securemesh.commander

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securemesh.commander.core.ui.SecureMeshTheme
import dev.securemesh.commander.navigation.SecureMeshRoot
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        val container = (application as SecureMeshApp).container
        val repository = container.repository
        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle()
            val activeFieldTest by repository.activeFieldTest.collectAsStateWithLifecycle()
            val protectSensitiveScreen = settings.secureScreen
            val keepScreenAwake = settings.keepScreenAwakeDuringTest && activeFieldTest?.running == true

            DisposableEffect(protectSensitiveScreen, keepScreenAwake) {
                if (protectSensitiveScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

                if (keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    window.setHideOverlayWindows(protectSensitiveScreen)
                }
                onDispose { }
            }

            SecureMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    SecureMeshRoot(repository = repository, mapManager = container.offlineMapManager)
                }
            }
        }
    }
}
