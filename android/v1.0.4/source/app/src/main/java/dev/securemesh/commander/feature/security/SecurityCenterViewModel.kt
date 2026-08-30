package dev.securemesh.commander.feature.security

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.AppSettings
import dev.securemesh.commander.domain.model.AuthenticationState
import dev.securemesh.commander.domain.model.BleDiagnostics
import dev.securemesh.commander.domain.model.SecureMeshSession
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SecurityCenterUiState(
    val settings: AppSettings = AppSettings(),
    val session: SecureMeshSession? = null,
    val ble: BleDiagnostics? = null,
    val overlayProtectionSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
) {
    val authenticated: Boolean get() = session?.authenticationState == AuthenticationState.AUTHENTICATED
    val bonded: Boolean? get() = ble?.bonded
}

class SecurityCenterViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    val uiState = combine(repository.settings, repository.session, repository.bleDiagnostics) { settings, session, ble ->
        SecurityCenterUiState(settings, session, ble)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityCenterUiState())

    fun setSecureScreen(enabled: Boolean) = update { it.copy(secureScreen = enabled) }
    fun setRememberTrustedNode(enabled: Boolean) = update { it.copy(rememberTrustedNode = enabled) }
    fun setAutoReconnect(enabled: Boolean) = update { it.copy(autoReconnect = enabled) }

    private fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repository.updateSettings(transform)
    }
}
