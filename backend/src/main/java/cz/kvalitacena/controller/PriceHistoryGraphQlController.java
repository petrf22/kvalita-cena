package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.service.PriceHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class PriceHistoryGraphQlController {

  private final PriceHistoryService priceHistoryService;

  @QueryMapping
  public PriceHistory priceHistory(@Argument Long productId, @Argument PriceKind priceKind,
      @Argument Long storeId, @Argument Integer days, Authentication authentication) {
    return priceHistoryService.history(productId, priceKind, storeId, days, authentication != null);
  }
}
