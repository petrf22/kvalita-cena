package cz.kvalitacena

import android.app.Application
import org.osmdroid.config.Configuration

class KvalitaACenaApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    AppContainer.init(this)

    // Cache dlaždic do appce vlastního (scoped) adresáře — bez tohle by osmdroid defaultně
    // sáhl na veřejné externí úložiště a vyžadoval WRITE_EXTERNAL_STORAGE, které appka nemá
    // (a nepotřebuje, viz AndroidManifest.xml). userAgentValue je nutný, jinak OSM tile server
    // dotazy odmítá (stejné pravidlo jako User-Agent na Nominatimu, docs/soukromi.md v backendu).
    Configuration.getInstance().apply {
      osmdroidBasePath = getExternalFilesDir("osmdroid")
      osmdroidTileCache = getExternalFilesDir("osmdroid/tiles")
      userAgentValue = packageName
    }
  }
}
