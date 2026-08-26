package cz.kvalitacena.service;

import java.net.URI;

/**
 * Validace tvaru odkazu na provozovnu (Store.url) — jen syntaxe, appka odkaz nikdy nenačítá,
 * takže není co ověřovat dál (docs/datovy-model.md). Sdílí ji StoreService (create) a
 * CatalogEditService (updateStore).
 */
public final class UrlValidation {

  private UrlValidation() {
  }

  public static boolean isValidStoreUrl(String url) {
    if (url == null || url.isBlank() || url.length() > 300) {
      return false;
    }
    try {
      URI uri = URI.create(url.trim());
      String scheme = uri.getScheme();
      return uri.getHost() != null && !uri.getHost().isBlank()
          && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
