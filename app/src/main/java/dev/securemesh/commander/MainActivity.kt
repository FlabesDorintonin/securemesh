package dev.securemesh.commander

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.securemesh.commander.core.ui.SecureMeshTheme
import dev.securemesh.commander.navigation.SecureMeshRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as SecureMeshApp).container.repository
        setContent {
            SecureMeshTheme {
                SecureMeshRoot(repository = repository)
            }
        }
    }
}
