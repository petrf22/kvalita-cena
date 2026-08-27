package cz.kvalitacena.service;

import cz.kvalitacena.controller.ProductSearchResult;
import cz.kvalitacena.db.repo.CategoryRepository;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.ProductSearchCriteria;
import cz.kvalitacena.db.repo.ProductSort;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.service.fx.FxRateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ProductSearchService} bez DB — samotný SQL dotaz ověřuje
 * {@code ProductSearchRepositoryIntegrationTest} (Testcontainers, nativní SQL Mockito
 * neověří). Tady jen to, co jde levně: ořez limitů, trim dotazu, blank dotaz nesahá na
 * repozitář vůbec, a CATEGORY_NOT_FOUND pro neplatné categoryId — fixní číselník, na rozdíl
 * od storeId/city tiché prázdno nesmí nastat.
 */
@ExtendWith(MockitoExtension.class)
class ProductSearchServiceTest {

  private static final Long VIEWER_ID = 42L;
  private static final Long CATEGORY_ID = 5L;

  @Mock
  private ProductRepository productRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private CategoryRepository categoryRepository;
  @Mock
  private ProductOverlayService productOverlayService;
  @Mock
  private FxRateService fxRateService;

  private ProductSearchService service() {
    return new ProductSearchService(productRepository, storeRepository, categoryRepository,
        productOverlayService, fxRateService);
  }

  private void givenEmptyResult() {
    when(productRepository.search(any())).thenReturn(List.of());
    when(productRepository.count(any(ProductSearchCriteria.class))).thenReturn(0L);
  }

  @Test
  void blankQueryReturnsEmptyResultWithoutTouchingRepository() {
    ProductSearchResult result = service().search("   ", null, null, null, "CZ",
        ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "cs");

    assertThat(result.items()).isEmpty();
    assertThat(result.totalCount()).isZero();
    verifyNoInteractions(productRepository);
  }

  @Test
  void nullQueryReturnsEmptyResultWithoutTouchingRepository() {
    ProductSearchResult result = service().search(null, null, null, null, "CZ",
        ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "cs");

    assertThat(result.items()).isEmpty();
    verifyNoInteractions(productRepository);
  }

  @Test
  void trimsQueryBeforeSearching() {
    givenEmptyResult();
    ArgumentCaptor<ProductSearchCriteria> captor = ArgumentCaptor.forClass(ProductSearchCriteria.class);

    service().search("  mléko  ", null, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "cs");

    verify(productRepository).search(captor.capture());
    assertThat(captor.getValue().query()).isEqualTo("mléko");
  }

  @Test
  void unknownCategoryThrowsCategoryNotFound() {
    when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(false);

    assertThatThrownBy(() -> service().search("mléko", null, null, CATEGORY_ID, "CZ",
        ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "cs"))
        .isInstanceOf(NotFoundException.class)
        .satisfies(ex -> assertThat(((NotFoundException) ex).getCode()).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND));
    // Tiché prázdno jako u storeId/city by tu mátlo — kategorie je fixní číselník.
    verify(productRepository, never()).search(any());
  }

  @Test
  void existingCategoryPassesThrough() {
    when(categoryRepository.existsById(CATEGORY_ID)).thenReturn(true);
    givenEmptyResult();
    ArgumentCaptor<ProductSearchCriteria> captor = ArgumentCaptor.forClass(ProductSearchCriteria.class);

    service().search("mléko", null, null, CATEGORY_ID, "CZ", ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "cs");

    verify(productRepository).search(captor.capture());
    assertThat(captor.getValue().categoryId()).isEqualTo(CATEGORY_ID);
  }

  @Test
  void clampsFirstAndOffsetToLimits() {
    givenEmptyResult();
    ArgumentCaptor<ProductSearchCriteria> captor = ArgumentCaptor.forClass(ProductSearchCriteria.class);

    service().search("mléko", null, null, null, "CZ", ProductSort.REPORT_COUNT, 999, -5, VIEWER_ID, null, "cs");

    verify(productRepository).search(captor.capture());
    assertThat(captor.getValue().first()).isEqualTo(50); // MAX_FIRST
    assertThat(captor.getValue().offset()).isZero();
  }

  @Test
  void passesLocaleIntoCriteria() {
    givenEmptyResult();
    ArgumentCaptor<ProductSearchCriteria> captor = ArgumentCaptor.forClass(ProductSearchCriteria.class);

    service().search("mléko", null, null, null, "CZ", ProductSort.REPORT_COUNT, 20, 0, VIEWER_ID, null, "en");

    verify(productRepository).search(captor.capture());
    assertThat(captor.getValue().locale()).isEqualTo("en");
  }
}
