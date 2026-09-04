package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.repo.ProductAliasConfirmationRepository;
import cz.kvalitacena.db.repo.ProductAliasRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;

/** Učí varianty názvu výhradně z úspěšných registrovaných cenových zápisů. */
@Service
@RequiredArgsConstructor
public class ProductAliasService {
  /** Pod touto délkou je vstup skoro jistě nedopsaný útržek hledání, ne skutečný název. */
  private static final int MIN_ALIAS_LENGTH = 4;

  private final ProductAliasRepository aliasRepository;
  private final ProductAliasConfirmationRepository confirmationRepository;
  private final CatalogProperties catalogProperties;
  private final EntityManager entityManager;

  @Transactional
  public void confirmFromObservation(Product product, AppUser submitter, String rawName) {
    if (submitter == null || rawName == null || !product.isGeneric()
        || product.getCatalogScope() == ProductScope.GLOBAL
        || product.getCatalogScope() == ProductScope.LEGACY_GLOBAL) {
      return;
    }
    String name = rawName.trim().replaceAll("\\s+", " ");
    if (name.length() < MIN_ALIAS_LENGTH || name.length() > 200) {
      return;
    }
    String normalizedName = normalized(name);
    String normalizedProductName = normalized(product.getName());
    if (normalizedName.equals(normalizedProductName) || isUnfinishedPrefixOf(normalizedName, normalizedProductName)) {
      return;
    }

    // Jediný SQL příkaz je odolný proti souběhu dvou prvních potvrzení a díky RETURNING dá
    // rovnou id vítězného řádku; chycení unique výjimky uvnitř JPA transakce by ji označilo
    // rollback-only a další COUNT by už nemohl proběhnout.
    Number aliasId = (Number) entityManager.createNativeQuery("""
        INSERT INTO core.product_alias(product_id,name)
        VALUES (:productId,:name)
        ON CONFLICT (product_id, core.norm_text(name))
        DO UPDATE SET name=core.product_alias.name
        RETURNING id
        """)
        .setParameter("productId", product.getId())
        .setParameter("name", name)
        .getSingleResult();

    confirmationRepository.insertIfAbsent(aliasId.longValue(), submitter.getId());
    aliasRepository.activateIfConfirmed(aliasId.longValue(), catalogProperties.getAliasConfirmations());
  }

  /**
   * Rozezná nedopsaný útržek vyhledávacího dotazu ("rohl" z "Rohlík celozrnný") od skutečné
   * kratší varianty názvu, která se shodou okolností jako podřetězec objeví taky ("Třicátník"
   * v "Chléb Třicátník celý") — ta druhá je platný alias, první ne. Rozdíl je na hranici slova:
   * candidate se porovná token po tokenu od začátku kanonického názvu; dokud se tokeny shodují
   * přesně, je to jen kratší/zkrácený zápis (v pořádku). Jakmile poslední token candidate
   * odpovídá jen NEÚPLNÉMU začátku odpovídajícího tokenu kanonického názvu, je to rozepsané
   * slovo uprostřed psaní, ne dokončený název.
   */
  private static boolean isUnfinishedPrefixOf(String candidate, String canonicalName) {
    String[] candidateTokens = candidate.split(" ");
    String[] canonicalTokens = canonicalName.split(" ");
    if (candidateTokens.length > canonicalTokens.length) {
      return false;
    }
    for (int i = 0; i < candidateTokens.length - 1; i++) {
      if (!candidateTokens[i].equals(canonicalTokens[i])) {
        return false;
      }
    }
    String lastCandidateToken = candidateTokens[candidateTokens.length - 1];
    String correspondingCanonicalToken = canonicalTokens[candidateTokens.length - 1];
    return !lastCandidateToken.equals(correspondingCanonicalToken)
        && correspondingCanonicalToken.startsWith(lastCandidateToken);
  }

  static String normalized(String value) {
    String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
        .trim().replaceAll("\\s+", " ");
  }
}
