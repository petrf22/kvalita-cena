package cz.kvalitacena.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.auth.AuthRepository
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class LoginStep { EMAIL, CODE }

/**
 * Krok se na CODE přepíná HNED po odeslání requestu, ne až po odpovědi serveru — odeslání OTP
 * e-mailu umí trvat (SMTP, viz backend OtpService/SmtpOtpMailSender) a čekání na EMAIL kroku
 * vypadalo jako zaseknutý formulář, takže uživatel odesílal znovu a narazil na cooldown
 * (OtpRateLimiter, 1 request/60 s). challengeUid appka drží jako [CompletableDeferred] —
 * verifyCode na něj počká, i kdyby uživatel opsal kód dřív, než odpověď na requestCode dorazí.
 * Web protějšek: frontend/src/app/features/login/login-page.ts.
 */
class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

  var step by mutableStateOf(LoginStep.EMAIL)
    private set
  var email by mutableStateOf("")
  var code by mutableStateOf("")
  // Souhlas s Podmínkami užití a Zásadami ochrany osobních údajů (docs/podminky-uziti.md,
  // docs/zasady-ochrany-osobnich-udaju.md) — vyžaduje se už u requestCode(), ne až u verifyCode(),
  // protože i requestOtp zpracovává e-mail (docs/soukromi.md, "Passwordless auth"), i když účet
  // vzniká JIT až při úspěšném ověření kódu.
  var consentAccepted by mutableStateOf(false)
  var sendingCode by mutableStateOf(false)
    private set
  var verifying by mutableStateOf(false)
    private set
  var errorMessage by mutableStateOf<UiText?>(null)
    private set
  /** Sekundy do dalšího možného odeslání — server je vrací v resendAfterSec (OtpRateLimiter,
   *  1 request/60 s na e-mail); 0 = tlačítko "Poslat znovu" je aktivní. */
  var resendCooldown by mutableStateOf(0)
    private set

  private var pendingChallenge: CompletableDeferred<String>? = null
  private var resendCooldownJob: Job? = null

  fun requestCode() {
    if (email.isBlank() || !consentAccepted || sendingCode) return
    sendingCode = true
    errorMessage = null
    // Přepnutí kroku NEČEKÁ na odpověď — viz komentář u třídy.
    step = LoginStep.CODE

    val challenge = CompletableDeferred<String>()
    pendingChallenge = challenge
    viewModelScope.launch {
      try {
        val response = authRepository.requestOtp(email.trim())
        challenge.complete(response.challengeUid)
        startResendCooldown(response.resendAfterSec.toInt())
      } catch (e: Exception) {
        challenge.completeExceptionally(e)
        pendingChallenge = null
        resendCooldownJob?.cancel()
        resendCooldown = 0
        // Zpět na zadání e-mailu — server odeslání odmítl (rate limit, pozastavený účet, …),
        // krok "zadej kód" by tu neměl co dělat.
        step = LoginStep.EMAIL
        errorMessage = e.toUiText()
      } finally {
        sendingCode = false
      }
    }
  }

  fun verifyCode(onSuccess: () -> Unit) {
    val challenge = pendingChallenge ?: return
    if (code.isBlank() || verifying) return
    verifying = true
    errorMessage = null
    viewModelScope.launch {
      try {
        val uid = challenge.await()
        authRepository.verifyOtp(uid, code.trim(), email.trim(), consentAccepted)
        onSuccess()
      } catch (e: Exception) {
        errorMessage = e.toUiText()
      } finally {
        verifying = false
      }
    }
  }

  fun backToEmail() {
    step = LoginStep.EMAIL
    code = ""
    errorMessage = null
    pendingChallenge = null
    resendCooldownJob?.cancel()
    resendCooldown = 0
  }

  private fun startResendCooldown(seconds: Int) {
    resendCooldownJob?.cancel()
    resendCooldownJob = viewModelScope.launch {
      var remaining = seconds
      while (remaining > 0) {
        resendCooldown = remaining
        delay(1000)
        remaining--
      }
      resendCooldown = 0
    }
  }
}
