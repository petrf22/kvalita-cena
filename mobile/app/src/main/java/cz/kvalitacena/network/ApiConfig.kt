package cz.kvalitacena.network

/**
 * 10.0.2.2 je alias emulátoru na localhost hostitelského stroje — viz
 * network_security_config.xml (cleartext povolený jen pro tuhle a localhost adresu).
 * V produkci se přepne na skutečnou HTTPS adresu backendu.
 */
object ApiConfig {
  const val BASE_URL = "http://10.0.2.2:8080"
}
