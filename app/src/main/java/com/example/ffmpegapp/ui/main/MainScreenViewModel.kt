package com.example.ffmpegapp.ui.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ffmpegapp.data.DataRepository
import com.example.ffmpegapp.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(private val dataRepository: DataRepository) : ViewModel() {
  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.data
      .map<List<String>, MainScreenUiState>(::Success)
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)
      
  private val _outputDirectoryUri = MutableStateFlow<Uri?>(null)
  val outputDirectoryUri: StateFlow<Uri?> = _outputDirectoryUri.asStateFlow()

  init {
    dataRepository.getOutputDirectoryUri()?.let { uriString ->
      _outputDirectoryUri.value = Uri.parse(uriString)
    }
  }

  fun onDirectorySelected(uri: Uri?, context: Context) {
    if (uri != null) {
      val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      context.contentResolver.takePersistableUriPermission(uri, takeFlags)
      dataRepository.saveOutputDirectoryUri(uri.toString())
      _outputDirectoryUri.value = uri
    } else {
      _outputDirectoryUri.value = null
    }
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<String>) : MainScreenUiState
}
