package cz.kvalitacena.crash

import android.util.Log

/**
 * Tenký wrapper nad `android.util.Log` s jedním tagem — appka do teď nikde nelogovala, takže
 * chyby odchycené v `catch` (na rozdíl od pádů, viz [CrashReporter]) nešly v logcatu vůbec
 * dohledat. Filtrovat appce jen: `adb logcat -s KvalitaACena:*` (kořenový `start-dev.sh` loguje
 * appku přes `--pid=`, takže tam se záznamy objeví bez úprav skriptu).
 *
 * Logcat je jen lokální diagnostika na zařízení, appka ho sama od sebe nikam neodesílá
 * (docs/soukromi.md, "žádná analytika třetích stran ani sledovací nástroje") — proto se sem
 * NIKDY nesmí zapsat hodnota proměnné dotazu (e-mail, OTP, refresh token), jen typ/text chyby
 * a nanejvýš samotný GraphQL dotaz (bez proměnných), který server odmítl.
 */
object AppLog {
  private const val TAG = "KvalitaACena"

  fun e(message: String, throwable: Throwable? = null) {
    Log.e(TAG, message, throwable)
  }
}
