package cz.kvalitacena.ui.receipt.capture

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * Zakrytý obdélník na dílu účtenky v podílech rozměru obrázku (0–1) — nezávislý na tom, v jaké
 * velikosti se díl zrovna zobrazuje. Do pixelů se vypálí až v telefonu před odesláním
 * (docs/soukromi.md, „Účtenky": zakrytý obsah na server vůbec nedorazí).
 */
@Serializable
data class MaskRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
  val isEmpty: Boolean get() = right - left <= 0f || bottom - top <= 0f

  fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Výchozí návrh zakrytí: spodní pás posledního dílu. Pod celkovou částkou bývá platba kartou
 * (maskované číslo), věrnostní karta a pokladní — docs/soukromi.md „Účtenky" navrhuje „pás pod
 * celkovou částkou"; kde přesně je částka, klient neví (text nečte), takže navrhne spodní
 * čtvrtinu a uživatel ji posune nebo smaže.
 */
const val DEFAULT_MASK_TOP = 0.75f

fun defaultMaskProposal(): MaskRect = MaskRect(0f, DEFAULT_MASK_TOP, 1f, 1f)

/**
 * Obdélník z tažení prstem ([startX],[startY] → [endX],[endY], v podílech 0–1) — tažení jde
 * libovolným směrem a mimo obrázek se ořízne na jeho okraj. Nechtěný dotyk (menší než
 * [minSize] v obou osách) nic nezakryje.
 */
fun maskFromDrag(startX: Float, startY: Float, endX: Float, endY: Float, minSize: Float = 0.02f): MaskRect? {
  val rect = MaskRect(
    left = min(startX, endX).coerceIn(0f, 1f),
    top = min(startY, endY).coerceIn(0f, 1f),
    right = max(startX, endX).coerceIn(0f, 1f),
    bottom = max(startY, endY).coerceIn(0f, 1f),
  )
  if (rect.right - rect.left < minSize && rect.bottom - rect.top < minSize) return null
  return rect.takeUnless { it.isEmpty }
}

/**
 * Pixelový obdélník pro vypálení masky do obrázku [width] × [height] — zaokrouhlený VEN, ať
 * na okraji zakrytí nezůstane napůl čitelný řádek pixelů. Vrací `[x0, y0, x1, y1)`.
 */
fun maskToPixels(mask: MaskRect, width: Int, height: Int): IntArray {
  val x0 = kotlin.math.floor(mask.left * width).toInt().coerceIn(0, width)
  val y0 = kotlin.math.floor(mask.top * height).toInt().coerceIn(0, height)
  val x1 = kotlin.math.ceil(mask.right * width).toInt().coerceIn(0, width)
  val y1 = kotlin.math.ceil(mask.bottom * height).toInt().coerceIn(0, height)
  return intArrayOf(x0, y0, x1, y1)
}

/** Přesun položky v seznamu (přeskládání dílů) — mimo rozsah vrátí seznam beze změny. */
fun <T> List<T>.moved(from: Int, to: Int): List<T> {
  if (from !in indices || to !in indices || from == to) return this
  val result = toMutableList()
  val item = result.removeAt(from)
  result.add(to, item)
  return result
}
