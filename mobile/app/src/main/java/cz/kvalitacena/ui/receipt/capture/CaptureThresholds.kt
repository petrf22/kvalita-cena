package cz.kvalitacena.ui.receipt.capture

/**
 * Prahy snímání účtenky. Zdroj pravdy je `docs/ai.md`, „Účtenky" (odstavec „Snímání na
 * klientovi") a limity serveru `app.receipt.*` tamtéž — tady jsou jen jako konstanty, stejné
 * pravidlo jako u prahů reputace (`docs/reputace.md`). Když se hodnota mění, mění se nejdřív
 * v dokumentu.
 */
object CaptureThresholds {
  /** Šířka zmenšeného šedotónového snímku, na kterém se měří ostrost a hledá účtenka (docs/ai.md). */
  const val ANALYSIS_WIDTH = 480

  /** Laplacianova variance na snímku šířky [ANALYSIS_WIDTH] — pod ní se snímek zahodí (docs/ai.md). */
  const val MIN_SHARPNESS = 60.0

  /** Nový výřez při posunu o tolik výšky záběru proti poslednímu uloženému, ~40 % překryv (docs/ai.md). */
  const val NEW_PART_SHIFT_RATIO = 0.60

  /** Šířka výřezu posílaného na server — `app.receipt.max-width` (docs/ai.md). */
  const val OUTPUT_MAX_WIDTH = 1600

  /** Kolik dílů smí mít jedna účtenka — `app.receipt.max-images-per-receipt` (docs/ai.md). */
  const val MAX_PARTS = 30

  /**
   * Kvalita JPEG výřezu. Šedotónový text při 85 zůstává ostrý a výřez vyjde v desítkách kB
   * (docs/ai.md: „výřez je šedotónový JPEG, typicky desítky kB"), daleko pod
   * `app.receipt.max-image-bytes` (4 MB).
   */
  const val JPEG_QUALITY = 85

  /**
   * Nejmenší podíl plochy záběru, který musí nalezený čtyřúhelník pokrýt, aby šlo o účtenku
   * a ne o ceduli v pozadí nebo odlesk. Účtenka zabírající méně než desetinu displeje je beztak
   * příliš malá na čitelný text.
   */
  const val MIN_QUAD_AREA_RATIO = 0.10

  /**
   * Nejmenší šířka pruhu účtenky (podíl šířky záběru) při záchranné detekci podle bočních hran
   * — dlouhá účtenka zblízka přesahuje záběr nahoře i dole a uzavřenou konturu nemá.
   */
  const val MIN_BAND_WIDTH_RATIO = 0.25

  /** O kolik (0–255) musí být papír světlejší než okolí, aby boční hrany platily. */
  const val MIN_BAND_CONTRAST = 25.0

  /**
   * Výška dílu při rozřezání dlouhého obrázku z galerie jako násobek jeho šířky. Odpovídá
   * příkladu z `docs/ai.md` (Billa 940 × 3430 px → 5 dílů s ~40% překryvem, tedy díl zhruba
   * čtvercový), na kterém OCR prošlo se součtem na haléř.
   */
  const val IMPORT_TILE_ASPECT = 1.0

  /**
   * Odezva `phaseCorrelate` (0–1), pod kterou se posun mezi dvěma snímky nebere vážně — záběr
   * bez struktury (bílý papír, rozmazání) dává náhodný vrchol a posun by jinak skákal.
   */
  const val MIN_SHIFT_RESPONSE = 0.05

  /**
   * Posun o víc než celý záběr od posledního dílu znamená, že mezi díly nezbyl překryv
   * a worker by je nespojil — appka pak řekne „vraťte telefon výš".
   */
  const val MAX_SHIFT_RATIO = 1.0

  /**
   * Jak daleko (podíl výšky dílu) smí řez dílu uhnout, aby padl do mezery mezi řádky textu
   * (`snapToGap`). Řádek účtenky má zhruba 3–4 % výšky čtvercového dílu, takže 5 % vždy
   * zasáhne aspoň jednu mezeru a překryv ~40 % se tím skoro nezmění.
   */
  const val CUT_SNAP_WINDOW_RATIO = 0.05

  /** Pixel tmavší než tahle hodnota (0–255) se počítá jako „inkoust" při hledání mezer mezi řádky. */
  const val INK_THRESHOLD = 128.0
}
