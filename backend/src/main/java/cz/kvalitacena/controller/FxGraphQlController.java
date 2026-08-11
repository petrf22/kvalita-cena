package cz.kvalitacena.controller;

import cz.kvalitacena.config.FxProperties;
import cz.kvalitacena.db.repo.ExchangeRateRepository;
import cz.kvalitacena.service.Messages;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class FxGraphQlController {

  private final FxProperties fxProperties;
  private final ExchangeRateRepository exchangeRateRepository;
  private final Messages messages;

  @QueryMapping
  public FxInfo fxInfo() {
    return new FxInfo(
        fxProperties.getDisplayCurrencies(),
        exchangeRateRepository.findTopByOrderByRateDateDesc().map(r -> r.getRateDate()).orElse(null),
        messages.get("attribution.cnb"));
  }
}
