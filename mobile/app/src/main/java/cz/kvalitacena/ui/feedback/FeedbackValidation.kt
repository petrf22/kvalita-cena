package cz.kvalitacena.ui.feedback

/**
 * Čistá validace formuláře zpětné vazby — mimo Compose, ať jde otestovat JUnitem bez
 * instrumentace (stejný vzor jako `ui/price/PriceRowValidation.kt`). Zrcadlí backendovou
 * validaci ve `FeedbackService.submit()` (max délka zprávy se ale nekontroluje natvrdo tady —
 * server je autoritativní, klient jen brání prázdnému odeslání a zjevně špatnému e-mailu).
 */

private val CONTACT_EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

fun isFeedbackMessageValid(message: String): Boolean = message.isNotBlank()

/** Prázdný kontaktní e-mail je platný (pole je nepovinné) — kontroluje se jen vyplněný tvar. */
fun isContactEmailValid(contactEmail: String): Boolean =
  contactEmail.isBlank() || CONTACT_EMAIL_PATTERN.matches(contactEmail.trim())

fun isFeedbackFormValid(message: String, contactEmail: String): Boolean =
  isFeedbackMessageValid(message) && isContactEmailValid(contactEmail)
