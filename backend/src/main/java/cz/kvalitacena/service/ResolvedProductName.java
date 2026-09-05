package cz.kvalitacena.service;

import cz.kvalitacena.controller.CatalogDataSource;

/**
 * Název zboží v jednom konkrétním jazyce i s tím, odkud je — víc než holý řetězec potřebuje
 * jak formulář (aby se poznalo, že nabízený název je z OFF a k tomu v němčině), tak detail
 * (štítek zdroje u názvu).
 *
 * @param lang       dvoupísmenný kód jazyka; {@code null} jen u „hlavního" názvu ze starého
 *                   snapshotu OFF, kde jazyk skutečně neznáme (viz {@code OffProduct.productName})
 * @param editedByMe název pochází z osobního patche prohlížejícího, ne z globální vrstvy
 */
public record ResolvedProductName(String lang, String name, CatalogDataSource source, boolean editedByMe) {
}
