package cz.kvalitacena.config;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * Čte {@code X-Display-Currency} do {@link graphql.GraphQLContext} pod klíčem {@link #CONTEXT_KEY}
 * (docs/lokalizace.md, "Kurzovní lístek a zobrazovací měna"). Hlavička, ne argument dotazu —
 * je to preference VIEWERA napříč celým dotazem, stejně jako {@code Accept-Language}
 * ({@link UserAwareLocaleResolver}), a {@code @BatchMapping} pole (Product.prices/myPrices)
 * argumenty na poli stejně nepodporují.
 *
 * <p>Neplatná/nepodporovaná hodnota (mimo {@code app.fx.display-currencies}) se tiše ignoruje —
 * žádná GraphQL chyba, stejný vzorec jako {@code SubmitObservationInput.currency}.
 */
@Component
@RequiredArgsConstructor
public class DisplayCurrencyInterceptor implements WebGraphQlInterceptor {

  public static final String CONTEXT_KEY = "displayCurrency";
  private static final String HEADER = "X-Display-Currency";

  private final FxProperties fxProperties;

  @Override
  public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
    String header = request.getHeaders().getFirst(HEADER);
    if (header != null && !header.isBlank()) {
      String currency = header.trim().toUpperCase(Locale.ROOT);
      if (fxProperties.getDisplayCurrencies().contains(currency)) {
        request.configureExecutionInput((executionInput, builder) ->
            builder.graphQLContext(ctx -> ctx.put(CONTEXT_KEY, currency)).build());
      }
    }
    return chain.next(request);
  }
}
