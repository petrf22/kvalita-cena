package cz.kvalitacena.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Appka žádá o obě polohová oprávnění naráz — Android to tak vyžaduje, když chce aplikace
 * nabídnout volbu „Přesná"/„Přibližná", a uživatel si v systémovém dialogu vybere.
 *
 * **Proč nestačí COARSE, jak to bylo do 2026-09:** přibližnou polohu systém snapuje na mřížku
 * o hraně kolem dvou kilometrů (naměřeno 1,5 km odchylky v emulátoru). Obchod, ve kterém člověk
 * právě stojí, se v okruhu 200–500 m takhle najít nedá — a souřadnice provozovny nastavené
 * tlačítkem „Použít mou polohu" byly stejně tak o kilometr vedle. Přibližná poloha appku
 * neshodí: hledání funguje dál, jen řekne, proč je výsledek prázdný, a doporučí širší okruh.
 */
val LOCATION_PERMISSIONS = arrayOf(
  Manifest.permission.ACCESS_FINE_LOCATION,
  Manifest.permission.ACCESS_COARSE_LOCATION,
)

/** Má appka polohu vůbec — přesnou, nebo aspoň přibližnou? */
fun hasAnyLocationPermission(context: Context): Boolean =
  granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
    granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

/**
 * Dostává appka jen zaokrouhlenou polohu? Rozhoduje o tom, jestli má UI vysvětlit, proč
 * v malém okruhu nic nenašla — bez toho by to vypadalo jako chyba appky.
 */
fun hasOnlyApproximateLocation(context: Context): Boolean =
  hasAnyLocationPermission(context) && !granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

private fun granted(context: Context, permission: String): Boolean =
  ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
