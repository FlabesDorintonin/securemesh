package dev.securemesh.commander.navigation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import kotlinx.coroutines.launch
class RootViewModel(private val repository:SecureMeshRepository):ViewModel(){val sos=repository.activeSos;val session=repository.session;fun acknowledge(id:String)=viewModelScope.launch{repository.acknowledgeSos(id)}}
