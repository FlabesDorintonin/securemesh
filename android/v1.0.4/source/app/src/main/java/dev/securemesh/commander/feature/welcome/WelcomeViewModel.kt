package dev.securemesh.commander.feature.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.TransportMode
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.launch

class WelcomeViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    val connection = repository.connectionState
    val session = repository.session
    val transportMode = repository.transportMode

    init {
        viewModelScope.launch { repository.attemptAutoReconnect() }
    }

    fun prepareBle(onReady: () -> Unit) = viewModelScope.launch {
        repository.cancelReconnect()
        repository.useTransport(TransportMode.BLE)
        onReady()
    }

    fun cancelReconnect() = viewModelScope.launch { repository.cancelReconnect() }
}
