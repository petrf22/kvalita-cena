package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.ObservationSource;
import cz.kvalitacena.db.entity.PriceObservation;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.PriceObservationService;
import cz.kvalitacena.service.ProductOverlayService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class ObservationGraphQlController {

  private final PriceObservationService priceObservationService;
  private final ProductRepository productRepository;
  private final ProductOverlayService productOverlayService;
  private final ViewerContextResolver viewerContextResolver;
  private final HttpServletRequest servletRequest;

  @MutationMapping
  public List<PriceObservation> submitObservations(@Argument SubmitObservationsInput input,
      Authentication authentication) {
    UUID publicUid = authentication != null && authentication.getPrincipal() instanceof UUID uid
        ? uid
        : null;
    return priceObservationService.submit(input, publicUid, resolveSource());
  }

  /**
   * core.product smí od {@code createProductFromOff} mít NULL name/category/unitBase/
   * netContentBase (OFF dodá efektivní hodnotu jen při čtení, docs/datovy-model.md) — vracet
   * z libovolné mutace/dotazu {@code PriceObservation.product} jako syrovou entitu by tak
   * mohlo poslat non-null pole se skutečným NULL (schema.graphqls, "Product.name: String!").
   * Jeden resolver pro celý typ pokrývá submitObservations, moderationObservations
   * i setObservationRejected najednou, ne až podle toho, kde se to zrovna projeví.
   */
  @BatchMapping(typeName = "PriceObservation", field = "product")
  public Map<PriceObservation, Product> product(List<PriceObservation> observations, Authentication authentication) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    List<Long> productIds = observations.stream().map(o -> o.getProduct().getId()).distinct().toList();
    Map<Long, Product> overlaidById = productOverlayService
        .applyOverlay(productRepository.findAllById(productIds), viewerId).stream()
        .collect(Collectors.toMap(Product::getId, Function.identity()));
    Map<PriceObservation, Product> result = new LinkedHashMap<>();
    observations.forEach(o -> result.put(o, overlaidById.get(o.getProduct().getId())));
    return result;
  }

  private ObservationSource resolveSource() {
    String clientKind = servletRequest.getHeader("X-Client-Kind");
    return "ANDROID".equalsIgnoreCase(clientKind) ? ObservationSource.MOBILE : ObservationSource.WEB;
  }
}
