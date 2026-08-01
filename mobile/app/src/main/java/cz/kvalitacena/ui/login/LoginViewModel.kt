package cz.kvalitacena.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.auth.AuthRepository
import kotlinx.coroutines.launch

enum class LoginStep { EMAIL, CODE }

class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

  var step by mutableStateOf(LoginStep.EMAIL)
    private set
  var email by mutableStateOf("")
  var code by mutableStateOf("")
  var loading by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<String?>(null)
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
        errorMessage = "Nepodařilo se odeslat kód. Zkus to prosím znovu za chvíli."
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
        errorMessage = "Kód je neplatný nebo vypršel. Zkus to prosím znovu."
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
