package cz.kvalitacena.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val PREFS_NAME = "kvalita_a_cena_auth"
private const val KEY_REFRESH_TOKEN = "refresh_token"

/**
 * Refresh token je jediná věc, která se na Androidu trvale ukládá — access token žije jen
 * v paměti procesu (viz docs/soukromi.md v backendu, "TTL refresh: 180 dní"). Šifrování
 * klíčem v Android Keystore (EncryptedSharedPreferences), ne obyčejné SharedPreferences.
 */
class TokenStore(context: Context) {

  private val prefs: SharedPreferences by lazy {
    val masterKey = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

    EncryptedSharedPreferences.create(
      context,
      PREFS_NAME,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  fun saveRefreshToken(token: String) {
    prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
  }

  fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

  fun clear() {
    prefs.edit().remove(KEY_REFRESH_TOKEN).apply()
  }
}
