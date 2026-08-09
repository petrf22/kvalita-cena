package cz.kvalitacena.service;

import cz.kvalitacena.controller.CompanyInfo;

/**
 * Veřejný rejstřík ekonomických subjektů pro předvyplnění formuláře obchodu podle IČO/NIP —
 * per zemi (docs/lokalizace.md), obdoba {@link CompanyIdValidator}. Zatím jen {@link
 * AresService} pro CZ; SK/PL zatím žádný registr nemají zapojený.
 */
public interface CompanyRegistry {
  String country();

  /** @return údaje o firmě, nebo {@code null} — nenalezeno, nebo je registr nedostupný. */
  CompanyInfo lookup(String companyId);
}
