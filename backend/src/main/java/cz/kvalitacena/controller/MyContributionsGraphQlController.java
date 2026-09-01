package cz.kvalitacena.controller;

import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.MyContributionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * "Moje příspěvky" — čtecí vrstva nad vlastní uživatelskou vrstvou (docs/datovy-model.md,
 * "Uživatelská vrstva nad globálními daty") a nad vlastními recenzemi. Anonym nemá co vypsat,
 * proto všech pět dotazů vyžaduje přihlášení, na rozdíl od {@code product}/{@code store}, které
 * fungují i bez něj (viz {@link ProductGraphQlController}/{@link StoreGraphQlController}).
 * {@code myReviews} vidí i vlastní recenze skryté moderací — na rozdíl od
 * {@code Query.productReviews} ({@link ProductReviewGraphQlController}), který cizí skryté
 * recenze nikomu neukáže.
 */
@Controller
@RequiredArgsConstructor
public class MyContributionsGraphQlController {

  private final MyContributionsService myContributionsService;
  private final ViewerContextResolver viewerContextResolver;

  @QueryMapping
  public MyProductResult myProducts(@Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    return myContributionsService.myProducts(requireLoggedIn(authentication), first, offset);
  }

  @QueryMapping
  public MyStoreResult myStores(@Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    return myContributionsService.myStores(requireLoggedIn(authentication), first, offset);
  }

  @QueryMapping
  public MyObservationResult myObservations(@Argument Integer first, @Argument Integer offset,
      Authentication authentication,
      @ContextValue(name = "displayCurrency", required = false) String displayCurrency) {
    return myContributionsService.myObservations(requireLoggedIn(authentication), first, offset, displayCurrency);
  }

  @QueryMapping
  public MyEditResult myEdits(@Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    return myContributionsService.myEdits(requireLoggedIn(authentication), first, offset);
  }

  @QueryMapping
  public MyReviewResult myReviews(@Argument Integer first, @Argument Integer offset,
      Authentication authentication) {
    return myContributionsService.myReviews(requireLoggedIn(authentication), first, offset);
  }

  private Long requireLoggedIn(Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.userId() == null) {
      throw new UnauthorizedException(ErrorCode.CONTRIBUTIONS_REQUIRE_LOGIN);
    }
    return viewer.userId();
  }
}
