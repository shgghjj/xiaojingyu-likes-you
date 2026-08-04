package com.pockettavern.app.ui.screens.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettavern.app.data.repository.LocalRepository
import com.pockettavern.app.domain.model.Character
import com.pockettavern.app.domain.model.Result
import com.pockettavern.app.extensions.ExtensionManager
import com.pockettavern.app.extensions.JsExtensionHost
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isReady: Boolean = false,
    val characters: List<Character> = emptyList(),
    val characterAvatarUrls: Map<String, String?> = emptyMap(),
    val isLoadingCharacters: Boolean = false,
    val selectedCharacter: Character? = null,
    val showDeleteDialog: Boolean = false,
    val characterToDelete: Character? = null,
    val showActionMenu: Boolean = false,
    val actionMenuCharacter: Character? = null,
    val error: String? = null,
    // Standalone mode — always connected locally
    val isConnected: Boolean = true,
    val panelRegistrations: Map<String, JsExtensionHost.PanelRegistration> = emptyMap()
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val localRepository: LocalRepository,
    private val extensionManager: ExtensionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            extensionManager.panelRegistrations.collect { panels ->
                _uiState.update { it.copy(panelRegistrations = panels) }
            }
        }
        // Rebuild Room index and load characters on first launch
        viewModelScope.launch {
            localRepository.rebuildCharacterIndex()
            _uiState.update { it.copy(isReady = true) }
            loadCharacters()
        }
    }

    fun loadCharacters() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCharacters = true) }

            when (val result = localRepository.getCharacters()) {
                is Result.Success -> {
                    // 酒馆彻底隔离小女友：从角色列表过滤掉小女友专属卡，酒馆只是一个纯粹的酒馆
                    val characters = result.data.filterNot { it.avatar == "girlfriend_card.png" }
                    val avatarUrls = characters.associate { char ->
                        val key = char.avatar ?: char.name
                        key to localRepository.getAvatarUri(char.avatar ?: "${char.name}.png").toString()
                    }
                    _uiState.update {
                        it.copy(
                            characters = characters,
                            characterAvatarUrls = avatarUrls,
                            isLoadingCharacters = false
                        )
                    }
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isLoadingCharacters = false, error = result.exception.message)
                    }
                }
            }
        }
    }

    fun selectCharacter(character: Character) {
        _uiState.update { it.copy(selectedCharacter = character) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedCharacter = null) }
    }

    fun showActionMenu(character: Character) {
        _uiState.update { it.copy(showActionMenu = true, actionMenuCharacter = character) }
    }

    fun dismissActionMenu() {
        _uiState.update { it.copy(showActionMenu = false, actionMenuCharacter = null) }
    }

    fun showDeleteConfirmation(character: Character) {
        _uiState.update {
            it.copy(
                showDeleteDialog = true,
                characterToDelete = character,
                showActionMenu = false,
                actionMenuCharacter = null
            )
        }
    }

    fun deleteCharacter() {
        val character = _uiState.value.characterToDelete ?: return
        viewModelScope.launch {
            when (localRepository.deleteCharacter(character.avatar ?: "${character.name}.png")) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            showDeleteDialog = false,
                            characterToDelete = null,
                            selectedCharacter = if (it.selectedCharacter?.name == character.name) null else it.selectedCharacter
                        )
                    }
                    loadCharacters()
                }
                is Result.Error -> {
                    _uiState.update { it.copy(showDeleteDialog = false, characterToDelete = null) }
                }
            }
        }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false, characterToDelete = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
