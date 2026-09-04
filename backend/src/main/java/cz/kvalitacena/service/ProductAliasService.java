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
    if (name.length() < 2 || name.length() > 200 || normalized(name).equals(normalized(product.getName()))) {
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

  static String normalized(String value) {
    String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
    return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
        .trim().replaceAll("\\s+", " ");
  }
}
