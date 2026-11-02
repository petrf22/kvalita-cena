package cz.kvalitacena.db.entity;

/** Rozsah strojového tokenu (auth.api_token, docs/ai.md, „Účtenky"). */
public enum ApiTokenScope {
  /** Worker účtenek — lease, obrázky, výsledek (/api/worker/receipts/**). */
  RECEIPT_WORKER,
  /**
   * Kurátor označení z účtenek (tools/uctenky/curate.py, /api/curator/**) — vidí jen
   * agregovaná nespárovaná označení a smí zapsat mapování označení → zboží. Oddělený od
   * workeru, ať token na stroji s OCR nemůže měnit katalog.
   */
  LABEL_CURATOR;

  /** Role Spring Security, kterou token dostane. */
  public String role() {
    return "ROLE_" + name();
  }
}
