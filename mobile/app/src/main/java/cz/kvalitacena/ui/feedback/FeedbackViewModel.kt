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

  /** [diagnostics] se posílá jen když uživatel výslovně zaškrtl [attachCrashReport] — nikdy automaticky. */
  fun submit(diagnostics: String?) {
    if (!canSubmit) return

    submitting = true
    submitError = null
    viewModelScope.launch {
      try {
        graphQlClient.submitFeedback(
          FeedbackInput(
            category = category,
            message = message.trim(),
            contactEmail = contactEmail.trim().ifBlank { null },
            pageRef = pageRef,
            appVersion = BuildConfig.VERSION_NAME,
            diagnostics = if (attachCrashReport) diagnostics else null,
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
