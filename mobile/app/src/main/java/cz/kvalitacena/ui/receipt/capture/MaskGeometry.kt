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
 * čtvrtinu a uživatel ji posune, zmenší, vypne nebo smaže. Proto se v kontrole kreslí jako
 * průsvitný šrafovaný návrh, ne černě — falešný poplach (dole nic citlivého, jen položky) musí
 * být vidět.
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

/** Nejmenší rozměr masky po změně velikosti (podíl 0–1) — menší by šla jen těžko chytit prstem. */
const val MIN_MASK_SIZE = 0.02f

enum class MaskCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Kam prst při začátku tažení v editoru zakrytí trefil (podle webového `receipt-mask-editor`). */
sealed interface MaskHit {
  /** Roh masky — tažení mění velikost. */
  data class Corner(val index: Int, val corner: MaskCorner) : MaskHit

  /** Vnitřek masky — tažení ji posouvá. */
  data class Inside(val index: Int) : MaskHit

  /** Volná plocha — tažení kreslí novou masku. */
  data object None : MaskHit
}

/**
 * Co je pod prstem v bodě [x],[y] (podíly 0–1). Rohy mají přednost před vnitřkem a horní
 * (později přidaná) maska před spodní — stejné pořadí, v jakém se kreslí. [radiusX]/[radiusY]
 * je dosah úchytu v podílech šířky a výšky (dotykový cíl 48 dp na obrázku libovolného tvaru).
 */
fun hitTest(masks: List<MaskRect>, x: Float, y: Float, radiusX: Float, radiusY: Float): MaskHit {
  for (index in masks.indices.reversed()) {
    val mask = masks[index]
    val corners = listOf(
      MaskCorner.TOP_LEFT to (mask.left to mask.top),
      MaskCorner.TOP_RIGHT to (mask.right to mask.top),
      MaskCorner.BOTTOM_LEFT to (mask.left to mask.bottom),
      MaskCorner.BOTTOM_RIGHT to (mask.right to mask.bottom),
    )
    val corner = corners.firstOrNull { (_, point) ->
      kotlin.math.abs(x - point.first) <= radiusX && kotlin.math.abs(y - point.second) <= radiusY
    }
    if (corner != null) return MaskHit.Corner(index, corner.first)
  }
  val inside = masks.indexOfLast { it.contains(x, y) }
  return if (inside >= 0) MaskHit.Inside(inside) else MaskHit.None
}

/**
 * Přepínač „Zakrýt" po úpravě masek: nově nakreslené zakrytí ho zapne (kdo kreslí obdélník přes
 * číslo karty, chce ho zakrýt), posun ani smazání existující masky ho nemění. Stejně jako web
 * (`masksEnabledAfterEdit` v `receipt-mask.ts`).
 */
fun masksEnabledAfterEdit(wasEnabled: Boolean, oldCount: Int, newCount: Int): Boolean =
  wasEnabled || newCount > oldCount

/** Posun o [dx],[dy] (podíly) se zachovanou velikostí — u okraje obrázku se zastaví. */
fun MaskRect.movedBy(dx: Float, dy: Float): MaskRect {
  val width = right - left
  val height = bottom - top
  val newLeft = (left + dx).coerceIn(0f, 1f - width)
  val newTop = (top + dy).coerceIn(0f, 1f - height)
  return MaskRect(newLeft, newTop, newLeft + width, newTop + height)
}

/**
 * Roh [corner] přetažený do bodu [x],[y] — protější roh zůstává na místě, maska nejde
 * „převrátit" přes něj ani zmenšit pod [MIN_MASK_SIZE] a neopustí obrázek.
 */
fun MaskRect.resizedTo(corner: MaskCorner, x: Float, y: Float): MaskRect {
  val cx = x.coerceIn(0f, 1f)
  val cy = y.coerceIn(0f, 1f)
  return when (corner) {
    MaskCorner.TOP_LEFT -> copy(left = min(cx, right - MIN_MASK_SIZE), top = min(cy, bottom - MIN_MASK_SIZE))
    MaskCorner.TOP_RIGHT -> copy(right = max(cx, left + MIN_MASK_SIZE), top = min(cy, bottom - MIN_MASK_SIZE))
    MaskCorner.BOTTOM_LEFT -> copy(left = min(cx, right - MIN_MASK_SIZE), bottom = max(cy, top + MIN_MASK_SIZE))
    MaskCorner.BOTTOM_RIGHT -> copy(right = max(cx, left + MIN_MASK_SIZE), bottom = max(cy, top + MIN_MASK_SIZE))
  }
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
