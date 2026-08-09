package cz.kvalitacena.network

import cz.kvalitacena.BuildConfig

/**
 * Adresa backendu je per-buildType `buildConfigField` (viz app/build.gradle.kts) — debug
 * `http://10.0.2.2:8080` (10.0.2.2 je alias emulátoru na localhost hostitelského stroje,
 * cleartext povolený jen tady, viz src/debug/res/xml/network_security_config.xml), release
 * `https://api.kvalitacena.cz` (docs/vydani.md — zatím neexistující produkční backend).
 */
object ApiConfig {
  val BASE_URL: String = BuildConfig.BASE_URL
}
