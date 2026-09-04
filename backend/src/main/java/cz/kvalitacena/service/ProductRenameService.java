package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Moderátorská oprava KANONICKÉHO názvu bezkódové položky.
 *
 * <p>Proč vlastní mutace a ne {@code updateProduct}: ta ukládá změnu do
 * {@code core.product_user_edit}, což je patch JEDNOHO uživatele ({@link ProductOverlayService})
 * — u zboží s EANem to dává smysl (identitu nese kód, název je jen popis), ale u bezkódové
 * položky JE název její identita. Překlep v kanonickém názvu by tak šlo opravit jen sám sobě,
 * ostatní by dál viděli chybu a zakládali vedle ní duplicity.
 *
 * <p>Původní název se nezahazuje, ale zůstává jako ACTIVE alias — kdo si ho pamatuje, najde
 * položku dál. Stejné pravidlo jako u {@code ProductMergeService.mergeAliases}: moderátorem
 * potvrzený název je užitečný alias i bez komunitních hlasů.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRenameService {

  /** core.product.name je VARCHAR(200), stejně jako core.product_alias.name. */
  private static final int MAX_NAME_LENGTH = 200;

  private final ProductRepository productRepository;
  private final DuplicateLookupService duplicateLookupService;
  private final JdbcTemplate jdbcTemplate;

  @Transactional
  public Product rename(Long productId, String rawName, Long moderatorUserId) {
    String name = rawName == null ? "" : rawName.trim().replaceAll("\\s+", " ");
    if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
      throw new ValidationException(ErrorCode.PRODUCT_NAME_EMPTY);
    }
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new NotFoundException(ErrorCode.MODERATION_RECORD_NOT_FOUND));
    if (!product.isGeneric() || product.getStatus() == ProductStatus.MERGED
        || product.getStatus() == ProductStatus.REJECTED) {
      throw new ValidationException(ErrorCode.MODERATION_PRODUCT_RENAME_INVALID);
    }

    String previousName = product.getName();
    product.setName(name);
    try {
      productRepository.saveAndFlush(product);
    } catch (DataIntegrityViolationException e) {
      // uq_product_generic_name_scope — v tomtéž rozsahu a kategorii už takový název je.
      // Sloučit dvě položky do jedné umí mergeProducts, přejmenování na to není nástroj.
      throw duplicateOf(name, product);
    }

    // Podmínka v SQL ošetří i změnu, která se liší jen diakritikou/velikostí písmen — tam by
    // alias byl duplicitou kanonického názvu a uq_product_alias_name by ho stejně odmítl.
    jdbcTemplate.update("""
        INSERT INTO core.product_alias(product_id, name, status, activated_at)
        SELECT ?, ?, 'ACTIVE', CURRENT_TIMESTAMP
        WHERE core.norm_text(?) <> core.norm_text(?)
        ON CONFLICT (product_id, core.norm_text(name))
        DO UPDATE SET status = 'ACTIVE', activated_at = COALESCE(core.product_alias.activated_at, CURRENT_TIMESTAMP)
        """, productId, previousName, previousName, name);

    log.info("Moderátor {} přejmenoval bezkódový produkt {} z \"{}\" na \"{}\"",
        moderatorUserId, productId, previousName, name);
    return product;
  }

  private DuplicateException duplicateOf(String name, Product product) {
    Long scopeStoreId = product.getScopeStore() == null ? null : product.getScopeStore().getId();
    List<Product> similar = duplicateLookupService.findSimilarProducts(name, scopeStoreId);
    Long existingId = similar.stream()
        .map(Product::getId)
        .filter(id -> !id.equals(product.getId()))
        .findFirst()
        .orElse(null);
    return new DuplicateException(ErrorCode.DUPLICATE_GENERIC_PRODUCT, existingId);
  }
}
