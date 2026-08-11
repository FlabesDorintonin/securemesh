package dev.securemesh.commander.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.AppSettings
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SecureMeshRepository) : ViewModel() {
    val settings = repository.settings
    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { repository.updateSettings(transform) }
}
