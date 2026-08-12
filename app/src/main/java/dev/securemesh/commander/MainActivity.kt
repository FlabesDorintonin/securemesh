package dev.securemesh.commander

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
import dev.securemesh.commander.domain.model.AuthenticationState
import dev.securemesh.commander.navigation.SecureMeshRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as SecureMeshApp).container.repository
        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle()
            val session by repository.session.collectAsStateWithLifecycle()
            val protectSensitiveScreen = settings.secureScreen &&
                session?.authenticationState == AuthenticationState.AUTHENTICATED

            DisposableEffect(protectSensitiveScreen) {
                if (protectSensitiveScreen) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                onDispose { }
            }

            SecureMeshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) {
                    SecureMeshRoot(repository = repository)
                }
            }
        }
    }
}
