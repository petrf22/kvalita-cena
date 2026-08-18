package cz.kvalitacena.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Otevře mapu na souřadnicích provozovny přes `geo:` URI — nechává uživateli volbu vlastní
 * mapové appky a neváže projekt na Google (stejný důvod, proč appka nepoužívá Play Services
 * Fused Location, viz CLAUDE.md). V emulátoru nebo na telefonu bez mapové appky spadne zpět
 * na OpenStreetMap v prohlížeči.
 */
fun openMap(context: Context, lat: Double, lon: Double, label: String) {
  val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, geoUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (e: ActivityNotFoundException) {
    val osmUri = Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon")
    context.startActivity(Intent(Intent.ACTION_VIEW, osmUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

/** Odkaz do Open Food Facts / dalších otevřených databází — vždy v prohlížeči, appka data sama nestahuje. */
fun openUrl(context: Context, url: String) {
  context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Otevře e-mailovou appku s vyplněným příjemcem (`ui/about/AboutScreen.kt`). Telefon bez
 * e-mailové appky (typicky emulátor) `ActivityNotFoundException` tiše pohltí — kontakt je
 * i tak vidět jako text, jen nejde rovnou odeslat. */
fun openEmail(context: Context, address: String) {
  val uri = Uri.parse("mailto:$address")
  try {
    context.startActivity(Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  } catch (e: ActivityNotFoundException) {
    // Nic k udělání — uživatel má adresu vypsanou v textu a může si ji zkopírovat ručně.
  }
}
