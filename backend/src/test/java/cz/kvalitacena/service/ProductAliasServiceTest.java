package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.repo.ProductAliasConfirmationRepository;
import cz.kvalitacena.db.repo.ProductAliasRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductAliasServiceTest {
  @Mock ProductAliasRepository aliasRepository;
  @Mock ProductAliasConfirmationRepository confirmationRepository;
  @Mock EntityManager entityManager;
  @Mock Query query;

  private ProductAliasService service;

  @BeforeEach
  void setUp() {
    CatalogProperties properties = new CatalogProperties();
    properties.setAliasConfirmations(2);
    service = new ProductAliasService(aliasRepository, confirmationRepository, properties, entityManager);
  }

  @Test
  void localAliasIsConfirmedWithConfiguredThreshold() {
    Product product = Product.builder().id(7L).name("Chléb Třicátník celý").generic(true)
        .catalogScope(ProductScope.STORE).build();
    AppUser user = AppUser.builder().id(9L).build();
    when(entityManager.createNativeQuery(anyString())).thenReturn(query);
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.getSingleResult()).thenReturn(11L);

    service.confirmFromObservation(product, user, "  třicátník  ");

    verify(confirmationRepository).insertIfAbsent(11L, 9L);
    verify(aliasRepository).activateIfConfirmed(11L, 2);
  }

  @Test
  void globalAndCanonicalNamesAreIgnored() {
    AppUser user = AppUser.builder().id(9L).build();
    Product global = Product.builder().id(7L).name("Třicátník").generic(true)
        .catalogScope(ProductScope.LEGACY_GLOBAL).build();
    Product local = global.toBuilder().catalogScope(ProductScope.STORE).build();

    service.confirmFromObservation(global, user, "jiný název");
    service.confirmFromObservation(local, user, " TŘICÁTNÍK ");

    verifyNoInteractions(entityManager, confirmationRepository, aliasRepository);
  }

  @Test
  void normalizationMatchesDatabaseContractForCommonCzechInput() {
    assertThat(ProductAliasService.normalized("  Dršťková   POLÉVKA ")).isEqualTo("drstkova polevka");
  }
}
