package com.erikbeans.wifidatabase.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.erikbeans.wifidatabase.data.local.SubmissionMemory
import com.erikbeans.wifidatabase.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val submissionMemory: SubmissionMemory,
) : ViewModel() {

    var email by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    init {
        email = authRepository.signedInEmail()
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true
            authRepository.logout()
            submissionMemory.clear()
            busy = false
            onDone()
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            busy = true
            authRepository.deleteAccount()
            submissionMemory.clear()
            busy = false
            onDone()
        }
    }
}
