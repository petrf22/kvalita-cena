package cz.kvalitacena.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Zachytávání pádů BEZ třetí strany (docs/soukromi.md, "žádná analytika třetích stran ani
 * sledovací nástroje" — Crashlytics/Sentry by ten slib porušily). Stacktrace se zapíše do
 * jediného souboru v `filesDir` (appce vlastní úložiště, nikdy neopouští zařízení samo od
 * sebe) a při dalším startu appka nabídne uživateli, ať si ho VÝSLOVNĚ přiloží ke zpětné vazbě
 * (`ui/feedback/FeedbackScreen.kt`, checkbox s výchozí hodnotou nezaškrtnuto) — nic se neposílá
 * automaticky.
 *
 * [install] MUSÍ zavolat i původní handler dál (`previousHandler?.uncaughtException`), jinak by
 * appka po pádu jen tiše zůstala viset místo obvyklého ukončení procesu systémem.
 */
object CrashReporter {
  private const val FILE_NAME = "last_crash.txt"

  // Shodné se serverovým app.feedback.max-diagnostics-length (docs/nasazeni.md) — oříznutí tady
  // je jen kosmetické (server by přebytek stejně ořízl), ať uživatel nenosí v appce zbytečně
  // velký soubor.
  private const val MAX_LENGTH = 8000

  fun install(context: Context, appVersion: String) {
    val appContext = context.applicationContext
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching { writeReport(appContext, appVersion, throwable) }
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  private fun writeReport(context: Context, appVersion: String, throwable: Throwable) {
    val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
    val report = "verze $appVersion\n$stackTrace".take(MAX_LENGTH)
    reportFile(context).writeText(report)
  }

  /** Obsah posledního zaznamenaného pádu, nebo null, pokud žádný nečeká (obvyklý případ). */
  fun pendingReport(context: Context): String? {
    val file = reportFile(context)
    return if (file.exists()) file.readText() else null
  }

  /** Zavolat po úspěšném odeslání zpětné vazby s přiloženým záznamem, ať appka nenabízí tentýž pád znovu. */
  fun clearPendingReport(context: Context) {
    reportFile(context).delete()
  }

  private fun reportFile(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)
}
