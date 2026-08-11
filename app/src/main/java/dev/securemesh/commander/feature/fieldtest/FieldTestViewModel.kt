package dev.securemesh.commander.feature.fieldtest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.securemesh.commander.domain.model.*
import dev.securemesh.commander.domain.repository.SecureMeshRepository
import dev.securemesh.commander.domain.service.UiAccessPolicy
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FieldTestUiState(val nodes:List<MeshNode> = emptyList(),val localNodeId:NodeId?=null,val canRun:Boolean=false,val active:FieldTestSession?=null)
class FieldTestViewModel(private val repository:SecureMeshRepository):ViewModel(){
    val uiState=combine(repository.nodes,repository.session,repository.activeFieldTest){n,s,a->
        val allowed=UiAccessPolicy.canRunFieldTest(s)
        FieldTestUiState(UiAccessPolicy.visibleNodes(s,n),s?.localNodeIdentity?.nodeId,allowed,a.takeIf{allowed})
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),FieldTestUiState())
    val history=combine(repository.observeFieldTestHistory(),repository.session){history,session->
        if(!UiAccessPolicy.canRunFieldTest(session)) emptyList() else history.filter{it.config.source==session?.localNodeIdentity?.nodeId}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5_000),emptyList())
    fun start(c:FieldTestConfig)=viewModelScope.launch{if(uiState.value.canRun)repository.startFieldTest(c)}
    fun stop()=viewModelScope.launch{if(uiState.value.canRun)repository.stopFieldTest()}
    suspend fun exportCsv()=if(uiState.value.canRun)repository.exportFieldTestsCsv() else ""
    suspend fun exportJson()=if(uiState.value.canRun)repository.exportFieldTestsJson() else "[]"
}
