package cz.kvalitacena.ui.common

import java.time.Duration
import java.time.OffsetDateTime

/** "před 3 dny" místo syrového ISO data — pro seznam hledání a detail produktu. */
fun formatRelativeDate(iso: String?): String {
  if (iso == null) return "zatím nikdy"
  return try {
    val date = OffsetDateTime.parse(iso)
    val duration = Duration.between(date, OffsetDateTime.now())
    when {
      duration.toMinutes() < 1 -> "právě teď"
      duration.toHours() < 1 -> "před ${duration.toMinutes()} min"
      duration.toDays() < 1 -> "před ${duration.toHours()} h"
      duration.toDays() == 1L -> "včera"
      duration.toDays() < 7 -> "před ${duration.toDays()} dny"
      else -> date.toLocalDate().toString()
    }
  } catch (e: Exception) {
    iso
  }
}
