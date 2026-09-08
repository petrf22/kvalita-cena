package cz.kvalitacena.db.repo;

import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.AppUserStatus;
import cz.kvalitacena.db.entity.Category;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductStatus;
import cz.kvalitacena.db.entity.ProductStoreLabel;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.UnitBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mapování obchodního označení na katalogovou položku proti SKUTEČNÉ Postgres — Mockito by
 * neodhalilo ani {@code core.norm_text} v unikátním indexu, ani {@code pg_trgm} skóre, ani
 * to, že unikát je na označení v rozsahu, NE na dvojici (označení, zboží). Právě ten obrácený
 * směr proti {@code core.product_alias} je jádro návrhu (docs/rozvoj.md).
 */
@Testcontainers
@SpringBootTest
// Zápisy přes @Modifying dotazy potřebují transakci; testovací transakce se navíc po každém
// testu vrátí zpět, takže si testy vzájemně nešpiní unikátní označení.
@Transactional
class ProductStoreLabelIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired private ProductStoreLabelRepository labelRepository;
  @Autowired private ProductStoreLabelConfirmationRepository confirmationRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private CategoryRepository categoryRepository;
  @Autowired private RetailChainRepository chainRepository;
  @Autowired private AppUserRepository appUserRepository;

  @Test
  void unikatJeNaOznaceniVRozsahu_neNaDvojiciOznaceniZbozi() {
    RetailChain chain = anyChain();
    Product first = product("Rohlík tukový");
    Product second = product("Rohlík grahamový");
    labelRepository.saveAndFlush(ProductStoreLabel.builder()
        .productId(first.getId()).label("ROHLIK TUZ 43G").chainId(chain.getId()).build());

    // Tatáž zkratka pro jiné zboží v témže řetězci není legitimní stav, ale případ pro moderaci.
    assertThatThrownBy(() -> labelRepository.saveAndFlush(ProductStoreLabel.builder()
        .productId(second.getId()).label("rohlik tuz 43g").chainId(chain.getId()).build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void upsertVratiVlastnikaOznaceni_takzeJdePoznatKolizi() {
    RetailChain chain = anyChain();
    Product owner = product("Cheddar strouhaný");
    Product other = product("Cheddar plátky");
    Object[] created = labelRepository.upsertReturningIdAndProduct(owner.getId(),
        "CHEDDAR STROUH.150G", chain.getId(), null).getFirst();

    Object[] conflict = labelRepository.upsertReturningIdAndProduct(other.getId(),
        "CHEDDAR STROUH.150G", chain.getId(), null).getFirst();

    assertThat(((Number) conflict[0]).longValue()).isEqualTo(((Number) created[0]).longValue());
    assertThat(((Number) conflict[1]).longValue()).isEqualTo(owner.getId());
  }

  @Test
  void oznaceniZacneParovatAzPoDosazeniPrahu() {
    RetailChain chain = anyChain();
    Product product = product("Salát Garden");
    Object[] row = labelRepository.upsertReturningIdAndProduct(product.getId(),
        "ALB SALAT GARDEN165G", chain.getId(), null).getFirst();
    long labelId = ((Number) row[0]).longValue();

    Long first = user().getId();
    Long second = user().getId();

    // Anonymní potvrzení se do prahu nepočítá (parciální index WHERE user_id IS NOT NULL).
    confirmationRepository.insertIfAbsent(labelId, null);
    assertThat(labelRepository.activateIfConfirmed(labelId, 2)).isZero();
    assertThat(labelRepository.findActiveByLabel("ALB SALAT GARDEN165G", null, chain.getId()))
        .isEmpty();

    confirmationRepository.insertIfAbsent(labelId, first);
    confirmationRepository.insertIfAbsent(labelId, first); // týž účet podruhé se nepočítá
    assertThat(labelRepository.activateIfConfirmed(labelId, 2)).isZero();

    confirmationRepository.insertIfAbsent(labelId, second);
    assertThat(labelRepository.activateIfConfirmed(labelId, 2)).isOne();
    assertThat(labelRepository.findActiveByLabel("ALB SALAT GARDEN165G", null, chain.getId()))
        .map(ProductStoreLabel::getProductId)
        .contains(product.getId());
  }

  @Test
  void presnaShodaIgnorujeDiakritikuAVelikostPismen() {
    RetailChain chain = anyChain();
    Product product = product("Rajčata");
    activeLabel(product, chain, "RAJČATA HRANAČEK250G");

    assertThat(labelRepository.findActiveByLabel("rajcata hranacek250g", null, chain.getId()))
        .isPresent();
  }

  @Test
  void podobnostNajdeOznaceniIKdyzSeNeshodujePresne() {
    RetailChain chain = anyChain();
    Product product = product("Cheddar strouhaný");
    activeLabel(product, chain, "CHEDDAR STROUH.150G");

    List<Object[]> similar = labelRepository.findSimilar("CHEDDAR STROUHANY 150G", null,
        chain.getId(), 0.2);

    assertThat(similar).isNotEmpty();
    assertThat(((Number) similar.getFirst()[0]).longValue()).isEqualTo(product.getId());
  }

  @Test
  void rozsahJePravJedenZRetezceNeboProvozovny() {
    Product product = product("Rohlík");

    assertThatThrownBy(() -> labelRepository.saveAndFlush(ProductStoreLabel.builder()
        .productId(product.getId()).label("BEZ ROZSAHU").build()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private ProductStoreLabel activeLabel(Product product, RetailChain chain, String label) {
    Object[] row = labelRepository.upsertReturningIdAndProduct(product.getId(), label,
        chain.getId(), null).getFirst();
    long id = ((Number) row[0]).longValue();
    confirmationRepository.insertIfAbsent(id, user().getId());
    labelRepository.activateIfConfirmed(id, 1);
    return labelRepository.findById(id).orElseThrow();
  }

  private AppUser user() {
    String unique = UUID.randomUUID().toString();
    return appUserRepository.saveAndFlush(AppUser.builder()
        .emailHash(unique.getBytes())
        .emailEnc(unique.getBytes())
        .publicHandle(unique.substring(0, 30))
        .handleAdjective("blue")
        .handleNoun("stork")
        .handleNumber((short) 1)
        .status(AppUserStatus.ACTIVE)
        .build());
  }

  private RetailChain anyChain() {
    return chainRepository.findAll().stream().findFirst().orElseThrow();
  }

  private Product product(String name) {
    Category category = categoryRepository.findAll().stream().findFirst().orElseThrow();
    return productRepository.saveAndFlush(Product.builder()
        .name(name)
        .category(category)
        .unitBase(UnitBase.MASS)
        .netContentBase(BigDecimal.ONE)
        .status(ProductStatus.ACTIVE)
        .build());
  }
}
