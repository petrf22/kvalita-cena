package cz.kvalitacena.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.R
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.launch

enum class LoginStep { EMAIL, CODE }

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

  var step by mutableStateOf(LoginStep.EMAIL)
    private set
  var email by mutableStateOf("")
  var code by mutableStateOf("")
  var loading by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<UiText?>(null)
    private set

  private var challengeUid: String? = null

  fun requestCode(onSent: () -> Unit = {}) {
    if (email.isBlank()) return
    loading = true
    errorMessage = null
    viewModelScope.launch {
      try {
        val response = authRepository.requestOtp(email.trim())
        challengeUid = response.challengeUid
        step = LoginStep.CODE
        onSent()
      } catch (e: Exception) {
        errorMessage = UiText.Res(R.string.login_request_failed)
      } finally {
        loading = false
      }
    }
  }

  fun verifyCode(onSuccess: () -> Unit) {
    val uid = challengeUid ?: return
    if (code.isBlank()) return
    loading = true
    errorMessage = null
    viewModelScope.launch {
      try {
        authRepository.verifyOtp(uid, code.trim(), email.trim())
        onSuccess()
      } catch (e: Exception) {
        errorMessage = UiText.Res(R.string.login_verify_failed)
      } finally {
        loading = false
      }
    }
  }

  fun backToEmail() {
    step = LoginStep.EMAIL
    code = ""
    errorMessage = null
  }
}
