package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.CreateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.DataOrigin;
import cz.kvalitacena.db.entity.GeoSource;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.exception.ValidationException;
import cz.kvalitacena.security.CatalogRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Založení provozovny mimo skenování/GPS (zápis ceny doma zpětně nebo bez sdílení polohy) —
 * viz docs/datovy-model.md, "Identita provozovny". Vyžaduje přihlášení (docs/reputace.md, T1),
 * na rozdíl od submitObservation, které funguje i anonymně.
 */
@Service
@RequiredArgsConstructor
public class StoreService {

  private final StoreRepository storeRepository;
  private final RetailChainRepository retailChainRepository;
  private final AppUserRepository appUserRepository;
  private final PriceObservationRepository priceObservationRepository;
  private final CompanyIdValidators companyIdValidators;
  private final CatalogRateLimiter catalogRateLimiter;
  private final DuplicateLookupService duplicateLookupService;
  private final TrustLevelService trustLevelService;
  private final CatalogProperties catalogProperties;
  private final CountryResolver countryResolver;

  @Transactional
  public Store create(CreateStoreInput input, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException(ErrorCode.STORE_CREATE_REQUIRES_LOGIN);
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException(ErrorCode.ACCOUNT_GONE));

    if (input.name() == null || input.name().isBlank()) {
      throw new ValidationException(ErrorCode.STORE_NAME_REQUIRED);
    }
    if (input.city() == null || input.city().isBlank()) {
      throw new ValidationException(ErrorCode.STORE_CITY_REQUIRED);
    }
    String country = countryResolver.resolve(input.country(), user.getId());
    if (!countryResolver.isSupported(country)) {
      throw new ValidationException(ErrorCode.UNSUPPORTED_COUNTRY);
    }
    String ico = normalizeIco(input.ico());
    // Pro zemi bez validátoru (zatím žádnou jinou než CZ/SK/PL) se IČO/NIP uloží bez kontroly
    // tvaru — lepší než odmítnout zápis kvůli algoritmu, který appka ještě nezná.
    if (ico != null) {
      companyIdValidators.forCountry(country).ifPresent(validator -> {
        if (!validator.isValid(ico)) {
          throw new ValidationException(ErrorCode.COMPANY_ID_INVALID);
        }
      });
    }
    String url = blankToNull(input.url());
    if (url != null && !UrlValidation.isValidStoreUrl(url)) {
      throw new ValidationException(ErrorCode.STORE_URL_INVALID);
    }

    // Původ dat (docs/datovy-model.md, pravidlo 1): OSM_DERIVED znamená "název a adresa jsou
    // z OpenStreetMap", a to se bez odkazu na konkrétní záznam nedá tvrdit. Samotné
    // geokódování adresy OSM_DERIVED není — odtamtud je jen bod, ne text, proto se u něj
    // geoSource=OSM a data_origin=OWN nevylučují. OFF_DERIVED patří jen ke zboží; u obchodu
    // by ho odmítl až CHECK v databázi a chyba by se tvářila jako DUPLICATE_STORE.
    DataOrigin dataOrigin = input.dataOrigin() != null ? input.dataOrigin() : DataOrigin.OWN;
    if (dataOrigin == DataOrigin.OFF_DERIVED) {
      throw new ValidationException(ErrorCode.STORE_DATA_ORIGIN_INVALID);
    }
    if (dataOrigin == DataOrigin.OSM_DERIVED && (input.osmRef() == null || input.osmRef().isBlank())) {
      throw new ValidationException(ErrorCode.STORE_OSM_ORIGIN_REQUIRES_REF);
    }
    // geo_source je původ BODU, data_origin původ TEXTU — u převzaté provozovny může bod
    // pocházet od uživatele (posunul ho na mapě), proto se OSM jen doplní, když klient nic neposlal.
    GeoSource geoSource = input.geoSource() != null ? input.geoSource()
        : dataOrigin == DataOrigin.OSM_DERIVED ? GeoSource.OSM : GeoSource.COMMUNITY;

    if (!catalogRateLimiter.tryAcquireStoreCreation(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    RetailChain chain = null;
    if (input.chainId() != null) {
      chain = retailChainRepository.findById(input.chainId())
          .orElseThrow(() -> new NotFoundException(ErrorCode.CHAIN_NOT_FOUND));
    }

    // Práh důvěry (docs/reputace.md, etapa-1 aproximace T2) — nedůvěryhodný autor založí
    // provozovnu jako PENDING, dokud ji nepotvrdí víc přispěvatelů (promoteIfConfirmed níže).
    StoreStatus status = trustLevelService.isTrusted(user) ? StoreStatus.ACTIVE : StoreStatus.PENDING;

    // "Našli jsme podobné" je primárně krok v klientovi (searchStores/findSimilar) PŘED
    // odesláním téhle mutace — tady je jen tvrdá pojistka pro přesnou shodu po normalizaci
    // (uq_store_identity), kterou klient obejít nemůže ani omylem, ani úmyslně.
    Store store = Store.builder()
        .chain(chain)
        .name(input.name().trim())
        .street(blankToNull(input.street()))
        .city(input.city().trim())
        .postalCode(blankToNull(input.postalCode()))
        .country(country)
        .ico(ico)
        .lat(input.lat())
        .lon(input.lon())
        .geoSource(geoSource)
        .osmRef(input.osmRef())
        .dataOrigin(dataOrigin)
        .url(url)
        .status(status)
        .createdByUserId(user.getId())
        .build();

    try {
      return storeRepository.saveAndFlush(store);
    } catch (DataIntegrityViolationException e) {
      throw duplicateOf(store);
    }
  }

  /**
   * Obdoba {@link ProductCatalogService#promoteIfConfirmed} pro provozovny — počítá jen
   * přispěvatele JINÉ než autora (leave-one-out, docs/reputace.md), jinak by si zakladatel
   * PENDING obchod odemkl sám třemi vlastními zápisy.
   */
  @Transactional
  public void promoteIfConfirmed(Long storeId) {
    Store store = storeRepository.findById(storeId).orElse(null);
    if (store == null || store.getStatus() != StoreStatus.PENDING) return;

    Long authorId = store.getCreatedByUserId();
    long contributors = priceObservationRepository.countDistinctContributorsExcluding(
        storeId, authorId == null ? -1L : authorId);
    if (contributors >= catalogProperties.getDraftConfirmations()) {
      store.setStatus(StoreStatus.ACTIVE);
      storeRepository.save(store);
    }
  }

  private DuplicateException duplicateOf(Store rejected) {
    // Vlastní transakce (DuplicateLookupService) — po chybě uq_store_identity je aktuální DB
    // transakce na úrovni Postgresu "aborted", dotaz nad stejnou session by spadl.
    // existingId musí být skutečná kolize, ne první podobný název — klient ho nabízí jako
    // "použij existující" a jiná pobočka téhož řetězce by tu cenu dostala omylem.
    Long existingId = duplicateLookupService.findStoreByIdentity(rejected.getCountry(),
            rejected.getName(), rejected.getCity(), rejected.getStreet())
        .map(Store::getId)
        .orElseGet(() -> {
          List<Store> similar = duplicateLookupService.findSimilarStores(rejected.getName(), rejected.getCity());
          return similar.isEmpty() ? null : similar.get(0).getId();
        });
    return new DuplicateException(ErrorCode.DUPLICATE_STORE, existingId);
  }

  private String normalizeIco(String ico) {
    return blankToNull(ico);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
