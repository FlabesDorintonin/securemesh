package dev.securemesh.commander.feature.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RoutesUiState(val routes:List<MeshRoute> = emptyList(),val nodes:List<MeshNode> = emptyList(),val canView:Boolean=false,val canManage:Boolean=false)
class RoutesViewModel(private val repository:SecureMeshRepository):ViewModel(){
    val uiState=combine(repository.routes,repository.nodes,repository.session){r,n,s->
        RoutesUiState(UiAccessPolicy.visibleRoutes(s,r),UiAccessPolicy.visibleNodes(s,n),UiAccessPolicy.canShowRoutes(s),UiAccessPolicy.canManageRoutes(s))
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),RoutesUiState())
    private val _error=MutableStateFlow<String?>(null);val error=_error.asStateFlow()
    fun add(d:String,v:String)=viewModelScope.launch{if(!uiState.value.canManage){_error.value="MANAGE_ROUTES not granted";return@launch};repository.addStaticRoute(d,v).onFailure{_error.value=it.message}}
    fun remove(d:String)=viewModelScope.launch{if(!uiState.value.canManage){_error.value="MANAGE_ROUTES not granted";return@launch};repository.removeRoute(d).onFailure{_error.value=it.message}}
}
