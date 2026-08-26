package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.controller.CreateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.GeoSource;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Založení provozovny mimo skenování/GPS (docs/datovy-model.md, "Identita provozovny").
 * Vyžaduje přihlášení na rozdíl od submitObservation — anonym může zapsat cenu, ale ne
 * založit nový obchod (docs/reputace.md, T1).
 */
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {

  private static final Long USER_ID = 42L;
  private static final UUID PUBLIC_UID = UUID.randomUUID();

  @Mock
  private StoreRepository storeRepository;
  @Mock
  private RetailChainRepository retailChainRepository;
  @Mock
  private AppUserRepository appUserRepository;
  @Mock
  private PriceObservationRepository priceObservationRepository;
  @Mock
  private CatalogRateLimiter catalogRateLimiter;
  @Mock
  private DuplicateLookupService duplicateLookupService;
  @Mock
  private TrustLevelService trustLevelService;

  private final CompanyIdValidators companyIdValidators = new CompanyIdValidators(List.of(new IcoValidator()));
  private final CatalogProperties catalogProperties = new CatalogProperties();
  private final I18nProperties i18nProperties = new I18nProperties();

  private StoreService service() {
    catalogProperties.setDraftConfirmations(3);
    i18nProperties.setDefaultCountry("CZ");
    i18nProperties.setCountryCurrency(Map.of("CZ", "CZK", "SK", "EUR", "PL", "PLN"));
    // Messages je null — tenhle test volá jen resolve()/isSupported(), ne supportedCountries()
    // (jediné místo, které Messages skutečně potřebuje pro lokalizovaný název země).
    CountryResolver countryResolver = new CountryResolver(i18nProperties, appUserRepository, null);
    return new StoreService(storeRepository, retailChainRepository, appUserRepository,
        priceObservationRepository, companyIdValidators, catalogRateLimiter, duplicateLookupService,
        trustLevelService, catalogProperties, countryResolver);
  }

  private CreateStoreInput input(String name, String city) {
    return new CreateStoreInput(name, null, "Hlavní 1", city, "60200", "CZ", null,
        new BigDecimal("49.2"), new BigDecimal("16.6"), null, null, null);
  }

  @Test
  void anonymousCannotCreateStore() {
    assertThatThrownBy(() -> service().create(input("Obchod", "Brno"), null))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  void blankNameIsRejected() {
    givenLoggedInUser();
    assertThatThrownBy(() -> service().create(input("  ", "Brno"), PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void blankCityIsRejected() {
    givenLoggedInUser();
    assertThatThrownBy(() -> service().create(input("Obchod", " "), PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void malformedIcoIsRejected() {
    givenLoggedInUser();
    CreateStoreInput bad = new CreateStoreInput("Obchod", null, null, "Brno", null, "CZ",
        "123", null, null, null, null, null);
    assertThatThrownBy(() -> service().create(bad, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void malformedUrlIsRejected() {
    givenLoggedInUser();
    CreateStoreInput bad = new CreateStoreInput("Obchod", null, null, "Brno", null, "CZ",
        null, null, null, null, null, "not-a-url");
    assertThatThrownBy(() -> service().create(bad, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void validUrlIsStoredOnCreation() {
    givenLoggedInUser();
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(storeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreateStoreInput input = new CreateStoreInput("Obchod", null, null, "Brno", null, "CZ",
        null, null, null, null, null, "https://www.example.cz/prodejna");
    Store saved = service().create(input, PUBLIC_UID);

    assertThat(saved.getUrl()).isEqualTo("https://www.example.cz/prodejna");
  }

  @Test
  void rateLimitExceededThrows() {
    givenLoggedInUser();
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(false);
    assertThatThrownBy(() -> service().create(input("Obchod", "Brno"), PUBLIC_UID))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void successfulCreationNormalizesFieldsAndDefaultsGeoSourceToCommunity() {
    givenLoggedInUser();
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(storeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Store saved = service().create(input("  Obchod U Nádraží  ", "  Brno  "), PUBLIC_UID);

    assertThat(saved.getName()).isEqualTo("Obchod U Nádraží");
    assertThat(saved.getCity()).isEqualTo("Brno");
    assertThat(saved.getGeoSource()).isEqualTo(GeoSource.COMMUNITY);
    assertThat(saved.getCreatedByUserId()).isEqualTo(USER_ID);
    assertThat(saved.getStatus()).isEqualTo(StoreStatus.ACTIVE);

    ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
    org.mockito.Mockito.verify(storeRepository).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getName()).isEqualTo("Obchod U Nádraží");
  }

  @Test
  void untrustedAuthorCreatesPendingStore() {
    givenLoggedInUser();
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(false);
    when(storeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Store saved = service().create(input("Obchod", "Brno"), PUBLIC_UID);

    assertThat(saved.getStatus()).isEqualTo(StoreStatus.PENDING);
  }

  @Test
  void pendingStoreIsPromotedOnceOtherContributorsReachThreshold() {
    Long storeId = 5L;
    Store store = Store.builder().id(storeId).status(StoreStatus.PENDING).createdByUserId(USER_ID).build();
    when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
    // Leave-one-out (docs/reputace.md) — jen jiní přispěvatelé než autor se počítají.
    when(priceObservationRepository.countDistinctContributorsExcluding(storeId, USER_ID)).thenReturn(3L);

    service().promoteIfConfirmed(storeId);

    ArgumentCaptor<Store> captor = ArgumentCaptor.forClass(Store.class);
    org.mockito.Mockito.verify(storeRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(StoreStatus.ACTIVE);
  }

  @Test
  void pendingStoreStaysPendingBelowThreshold() {
    Long storeId = 5L;
    Store store = Store.builder().id(storeId).status(StoreStatus.PENDING).createdByUserId(USER_ID).build();
    when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
    when(priceObservationRepository.countDistinctContributorsExcluding(storeId, USER_ID)).thenReturn(2L);

    service().promoteIfConfirmed(storeId);

    org.mockito.Mockito.verify(storeRepository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void missingCountryFallsBackToViewerCountryNotDefault() {
    AppUser user = AppUser.builder().id(USER_ID).country("SK").build();
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(user));
    // CountryResolver čte vieweruv záznam znovu přes findById (jiná repository metoda než
    // findByPublicUid, kterou používá StoreService pro autorizaci) — obojí musí ukazovat na
    // stejného uživatele, jinak resolver spadne na app.i18n.default-country.
    when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(true);
    when(trustLevelService.isTrusted(any())).thenReturn(true);
    when(storeRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    CreateStoreInput input = new CreateStoreInput("Obchod", null, null, "Bratislava", null, null,
        null, null, null, null, null, null);
    Store saved = service().create(input, PUBLIC_UID);

    // CountryResolver.resolve: chybějící explicitní country -> app_user.country (SK), nikdy
    // rovnou app.i18n.default-country — jinak by slovenský uživatel dostal CZ/CZK bez ptaní.
    assertThat(saved.getCountry()).isEqualTo("SK");
  }

  @Test
  void unsupportedCountryIsRejected() {
    givenLoggedInUser();
    CreateStoreInput input = new CreateStoreInput("Obchod", null, null, "Brno", null, "AT",
        null, null, null, null, null, null);
    assertThatThrownBy(() -> service().create(input, PUBLIC_UID))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void duplicateIdentityIsReportedWithExistingStoreId() {
    givenLoggedInUser();
    when(catalogRateLimiter.tryAcquireStoreCreation(PUBLIC_UID)).thenReturn(true);
    when(storeRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_store_identity"));
    when(duplicateLookupService.findSimilarStores("Obchod", "Brno"))
        .thenReturn(List.of(Store.builder().id(99L).name("Obchod").city("Brno").build()));

    assertThatThrownBy(() -> service().create(input("Obchod", "Brno"), PUBLIC_UID))
        .isInstanceOf(DuplicateException.class)
        .satisfies(e -> assertThat(((DuplicateException) e).getExistingId()).isEqualTo(99L));
  }

  private void givenLoggedInUser() {
    when(appUserRepository.findByPublicUid(PUBLIC_UID)).thenReturn(Optional.of(AppUser.builder().id(USER_ID).build()));
  }
}
