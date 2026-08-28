package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.PriceObservationService;
import cz.kvalitacena.service.ProductOverlayService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@code PriceObservation.product} musí projít {@link ProductOverlayService} stejně jako
 * kterékoli jiné čtení core.product (CLAUDE.md, "core.product_observation je jádro aplikace") —
 * {@code createProductFromOff} zakládá řádky s NULL name/category/unitBase (docs/datovy-model.md,
 * "Open Food Facts"), takže vrácení syrové entity by poslalo GraphQL non-null pole
 * (schema.graphqls, "Product.name: String!") se skutečným NULL. Jeden {@code @BatchMapping}
 * pro celý typ pokrývá {@code submitObservations}, {@code moderationObservations} i
 * {@code setObservationRejected} najednou.
 */
@ExtendWith(MockitoExtension.class)
class ObservationGraphQlControllerTest {

  @Mock private PriceObservationService priceObservationService;
  @Mock private ProductRepository productRepository;
  @Mock private ProductOverlayService productOverlayService;
  @Mock private ViewerContextResolver viewerContextResolver;
  @Mock private HttpServletRequest servletRequest;

  private ObservationGraphQlController controller() {
    return new ObservationGraphQlController(priceObservationService, productRepository,
        productOverlayService, viewerContextResolver, servletRequest);
  }

  @Test
  void productFieldResolvesThroughOverlayNotRawEntity() {
    Product rawA = Product.builder().id(1L).build();
    Product rawB = Product.builder().id(2L).build();
    PriceObservation obsA = PriceObservation.builder().id(100L).product(rawA).build();
    PriceObservation obsB = PriceObservation.builder().id(200L).product(rawB).build();

    when(viewerContextResolver.resolve(any())).thenReturn(new ViewerContext(null, 42L, true, true));
    when(productRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(rawA, rawB));
    Product overlaidA = rawA.toBuilder().name("Efektivní název A").build();
    Product overlaidB = rawB.toBuilder().name("Efektivní název B").build();
    when(productOverlayService.applyOverlay(List.of(rawA, rawB), 42L)).thenReturn(List.of(overlaidA, overlaidB));

    Map<PriceObservation, Product> result = controller().product(List.of(obsA, obsB), null);

    assertThat(result.get(obsA)).isSameAs(overlaidA);
    assertThat(result.get(obsB)).isSameAs(overlaidB);
  }

  @Test
  void sameProductAcrossObservationsIsOverlaidOnlyOnce() {
    Product raw = Product.builder().id(1L).build();
    PriceObservation obsA = PriceObservation.builder().id(100L).product(raw).build();
    PriceObservation obsB = PriceObservation.builder().id(200L).product(raw).build();

    when(viewerContextResolver.resolve(any())).thenReturn(ViewerContext.ANONYMOUS);
    ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
    when(productRepository.findAllById(idsCaptor.capture())).thenReturn(List.of(raw));
    Product overlaid = raw.toBuilder().name("Efektivní název").build();
    when(productOverlayService.applyOverlay(List.of(raw), null)).thenReturn(List.of(overlaid));

    Map<PriceObservation, Product> result = controller().product(List.of(obsA, obsB), null);

    assertThat(idsCaptor.getValue()).containsExactly(1L);
    assertThat(result.get(obsA)).isSameAs(overlaid);
    assertThat(result.get(obsB)).isSameAs(overlaid);
  }

  @Test
  void anonymousViewerPassesNullUserIdToOverlay() {
    Product raw = Product.builder().id(1L).build();
    PriceObservation observation = PriceObservation.builder().id(100L).product(raw).build();

    when(viewerContextResolver.resolve(any())).thenReturn(ViewerContext.ANONYMOUS);
    when(productRepository.findAllById(List.of(1L))).thenReturn(List.of(raw));
    when(productOverlayService.applyOverlay(List.of(raw), null)).thenReturn(List.of(raw));

    controller().product(List.of(observation), null);

    org.mockito.Mockito.verify(productOverlayService).applyOverlay(List.of(raw), null);
  }
}
