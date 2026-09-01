package cz.kvalitacena.ui.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cz.kvalitacena.BuildConfig
import cz.kvalitacena.network.FeedbackInput
import cz.kvalitacena.network.GraphQlClient
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Jediný first-party kanál zpětné vazby (core.feedback, docs/nasazeni.md "Než pozvat první
 * lidi") — funguje i BEZ přihlášení, appka o přihlášení vůbec neví (server si autora dohledá
 * sám z tokenu, viz `AuthRepository`/`GraphQlClient` — `Authorization` hlavička jde, pokud je,
 * appka ji ale sama nezjišťuje). [pageRef] je orientační popis obrazovky, ze které se na
 * formulář vstoupilo (Nastavení/O aplikaci), appka na tom nic nerozhoduje.
 */
class FeedbackViewModel(
  private val graphQlClient: GraphQlClient,
  private val pageRef: String,
) : ViewModel() {

  var category by mutableStateOf("BUG")
  var message by mutableStateOf("")
  var contactEmail by mutableStateOf("")
  var attachCrashReport by mutableStateOf(false)

  var submitting by mutableStateOf(false)
    private set
  var submitSuccess by mutableStateOf(false)
    private set
  var submitError by mutableStateOf<UiText?>(null)
    private set

  val canSubmit: Boolean
    get() = isFeedbackFormValid(message, contactEmail) && !submitting

  // Proof-of-work (docs/nasazeni.md, obrana proti spamu) — výzva se vyžádá a řeší na pozadí
  // hned při vstupu na obrazovku (init), ať je nonce hotový dřív, než uživatel dopíše zprávu.
  // Appka bez PoW klidně funguje dál (required gating je na serveru) — nezdařené vyžádání
  // výzvy submit() jen pošle bez challenge/nonce.
  private var challengeToken: String? = null
  private var challengeNonce: String? = null
  private val challengeReady = viewModelScope.async {
    try {
      val challenge = graphQlClient.feedbackChallenge()
      challengeToken = challenge.token
      challengeNonce = ProofOfWork.solve(challenge.salt, challenge.difficulty)
    } catch (e: Exception) {
      // Appka pokračuje bez PoW — server rozhodne (required v prod, jen skóre v beta).
    }
  }

  /** [diagnostics] se posílá jen když uživatel výslovně zaškrtl [attachCrashReport] — nikdy automaticky. */
  fun submit(diagnostics: String?) {
    if (!canSubmit) return

    submitting = true
    submitError = null
    viewModelScope.launch {
      try {
        challengeReady.await()
        graphQlClient.submitFeedback(
          FeedbackInput(
            category = category,
            message = message.trim(),
            contactEmail = contactEmail.trim().ifBlank { null },
            pageRef = pageRef,
            appVersion = BuildConfig.VERSION_NAME,
            diagnostics = if (attachCrashReport) diagnostics else null,
            challenge = challengeToken,
            nonce = challengeNonce,
          ),
        )
        submitSuccess = true
      } catch (e: Exception) {
        submitError = e.toUiText()
      } finally {
        submitting = false
      }
    }
  }

  fun sendAnother() {
    submitSuccess = false
    message = ""
    contactEmail = ""
    attachCrashReport = false
  }
}
