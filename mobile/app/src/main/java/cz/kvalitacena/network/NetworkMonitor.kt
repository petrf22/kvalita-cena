package cz.kvalitacena.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Je telefon připojený? Pro offline stav obrazovek (docs/design.md, „Čtyři stavy"): v obchodě
 * bez signálu má appka říct, že se zápis odešle později, ne tvářit se jako chyba. Jen
 * `NET_CAPABILITY_INTERNET`, ne `VALIDATED` — ověření připojení u některých sítí trvá nebo
 * neprojde (firemní Wi-Fi) a appka by pak tvrdila „offline" i tam, kde server dosáhne.
 */
class NetworkMonitor(context: Context) {

  private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

  private val _online = MutableStateFlow(currentlyOnline())
  val online: StateFlow<Boolean> = _online

  init {
    connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        _online.value = true
      }

      override fun onLost(network: Network) {
        _online.value = currentlyOnline()
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      }
    })
  }

  private fun currentlyOnline(): Boolean {
    val manager = connectivity ?: return true
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
  }
}
