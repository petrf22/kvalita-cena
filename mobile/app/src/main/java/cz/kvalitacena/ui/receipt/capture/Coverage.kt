package cz.kvalitacena.ui.receipt.capture

import kotlin.math.max

/**
 * Co má uživatel právě udělat — pokyn nad náhledem kamery. Z téhle hodnoty se skládá text
 * (`ReceiptCaptureScreen`), logika je mimo Compose kvůli testům (`CoverageTest`).
 */
enum class CaptureHint {
  /** V záběru není nic, co by vypadalo jako účtenka. */
  FIND_RECEIPT,

  /** Účtenka je vidět, ale snímek je rozmazaný. */
  HOLD_STILL,

  /** Díl je uložený, pokračujte níž (hlavní stav dlouhé účtenky). */
  MOVE_DOWN,

  /** Telefon ujel o víc než celý záběr a mezi díly by chyběl překryv. */
  MOVE_BACK_UP,

  /** Dosažen limit dílů na jednu účtenku (`app.receipt.max-images-per-receipt`). */
  LIMIT_REACHED,
}

/**
 * Sledování pokrytí (coverage tracking, docs/rozvoj.md „Sběr snímků na klientovi"): sčítá svislý
 * posun obsahu mezi po sobě jdoucími snímky (`phaseCorrelate`) a řekne, kdy je čas uložit další
 * díl — při posunu o [CaptureThresholds.NEW_PART_SHIFT_RATIO] výšky záběru od posledního
 * uloženého, tedy s ~40% překryvem, ze kterého worker díly spojí.
 *
 * Posun je v podílech výšky záběru, kladný = telefon jede po účtence dolů (obsah v záběru
 * ujíždí nahoru, `phaseCorrelate` tedy vrací záporné dy). Couvnutí nahoru posun zase ubírá.
 */
class CoverageTracker(
  private val shiftRatio: Double = CaptureThresholds.NEW_PART_SHIFT_RATIO,
  private val maxShiftRatio: Double = CaptureThresholds.MAX_SHIFT_RATIO,
  private val maxParts: Int = CaptureThresholds.MAX_PARTS,
) {
  /** Posun od posledního uloženého dílu, v podílech výšky záběru. */
  var shiftSinceLastPart: Double = 0.0
    private set

  var partCount: Int = 0
    private set

  /**
   * Přičte posun obsahu mezi dvěma snímky. [dyPixels] je výsledek `phaseCorrelate` (posun
   * druhého snímku proti prvnímu), [frameHeight] výška snímku, na kterém se měřilo.
   * [response] pod [CaptureThresholds.MIN_SHIFT_RESPONSE] se ignoruje — posun je šum.
   */
  fun addFrameShift(dyPixels: Double, frameHeight: Int, response: Double) {
    if (frameHeight <= 0 || response < CaptureThresholds.MIN_SHIFT_RESPONSE) return
    // Před prvním dílem se nic nesčítá — první díl je horní okraj účtenky, ať je kde chce.
    if (partCount == 0) return
    shiftSinceLastPart = max(0.0, shiftSinceLastPart - dyPixels / frameHeight)
  }

  /** Průběh k dalšímu dílu, 0–1 — pro progress bar. */
  val progressToNextPart: Float
    get() = (shiftSinceLastPart / shiftRatio).coerceIn(0.0, 1.0).toFloat()

  /**
   * Uložit právě analyzovaný snímek jako další díl? První díl, jakmile je účtenka vidět
   * a ostrá; každý další až po dostatečném posunu, ne však po příliš velkém (chyběl by překryv).
   */
  fun shouldCapture(receiptFound: Boolean, sharp: Boolean): Boolean {
    if (!receiptFound || !sharp || partCount >= maxParts) return false
    if (partCount == 0) return true
    return shiftSinceLastPart in shiftRatio..maxShiftRatio
  }

  fun onPartSaved() {
    partCount++
    shiftSinceLastPart = 0.0
  }

  /** Díl byl v kontrole smazán nebo se snímá znovu od začátku. */
  fun reset(parts: Int = 0) {
    partCount = parts
    shiftSinceLastPart = 0.0
  }

  fun hint(receiptFound: Boolean, sharp: Boolean): CaptureHint = when {
    partCount >= maxParts -> CaptureHint.LIMIT_REACHED
    partCount > 0 && shiftSinceLastPart > maxShiftRatio -> CaptureHint.MOVE_BACK_UP
    !receiptFound -> if (partCount == 0) CaptureHint.FIND_RECEIPT else CaptureHint.MOVE_DOWN
    !sharp -> CaptureHint.HOLD_STILL
    partCount == 0 -> CaptureHint.HOLD_STILL
    else -> CaptureHint.MOVE_DOWN
  }
}
