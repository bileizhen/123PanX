package io.github.bileizhen.pan123x.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.bileizhen.pan123x.data.settings.UpdateRepository
import io.github.bileizhen.pan123x.data.settings.UpdateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpdateUiState(val checking: Boolean = false, val result: UpdateResult? = null)
class UpdateViewModel(private val repository: UpdateRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    fun check() {
        if (mutableState.value.checking) return
        mutableState.value = UpdateUiState(checking = true)
        viewModelScope.launch { try { mutableState.value = UpdateUiState(result = repository.check()) }
            finally { mutableState.value = mutableState.value.copy(checking = false) } }
    }
    fun dismiss() { if (!mutableState.value.checking) mutableState.value = UpdateUiState() }
}
