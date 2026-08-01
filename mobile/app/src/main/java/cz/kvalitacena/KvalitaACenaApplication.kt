package cz.kvalitacena

import android.app.Application

class KvalitaACenaApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppContainer.init(this)
  }
}
