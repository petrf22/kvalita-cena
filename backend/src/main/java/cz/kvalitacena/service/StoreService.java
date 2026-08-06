package cz.kvalitacena.service;

import cz.kvalitacena.config.CatalogProperties;
import cz.kvalitacena.controller.CreateStoreInput;
import cz.kvalitacena.db.entity.AppUser;
import cz.kvalitacena.db.entity.GeoSource;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.AppUserRepository;
import cz.kvalitacena.db.repo.PriceObservationRepository;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.DuplicateException;
import cz.kvalitacena.exception.NotFoundException;
import cz.kvalitacena.exception.TooManyRequestsException;
import cz.kvalitacena.exception.UnauthorizedException;
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
  private final IcoValidator icoValidator;
  private final CatalogRateLimiter catalogRateLimiter;
  private final DuplicateLookupService duplicateLookupService;
  private final TrustLevelService trustLevelService;
  private final CatalogProperties catalogProperties;

  @Transactional
  public Store create(CreateStoreInput input, UUID viewerPublicUid) {
    if (viewerPublicUid == null) {
      throw new UnauthorizedException("Založení obchodu vyžaduje přihlášení");
    }
    AppUser user = appUserRepository.findByPublicUid(viewerPublicUid)
        .orElseThrow(() -> new UnauthorizedException("Účet už neexistuje"));

    if (input.name() == null || input.name().isBlank()) {
      throw new IllegalArgumentException("Název obchodu je povinný");
    }
    if (input.city() == null || input.city().isBlank()) {
      throw new IllegalArgumentException("Město je povinné");
    }
    String ico = normalizeIco(input.ico());
    if (ico != null && !icoValidator.isValid(ico)) {
      throw new IllegalArgumentException("IČO nemá platný tvar (8 číslic, špatný kontrolní součet)");
    }
    if (!catalogRateLimiter.tryAcquireStoreCreation(viewerPublicUid)) {
      throw new TooManyRequestsException();
    }

    RetailChain chain = null;
    if (input.chainId() != null) {
      chain = retailChainRepository.findById(input.chainId())
          .orElseThrow(() -> new NotFoundException("Řetězec s tímto id neexistuje"));
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
        .country(input.country() == null || input.country().isBlank() ? "CZ" : input.country().trim())
        .ico(ico)
        .lat(input.lat())
        .lon(input.lon())
        .geoSource(input.geoSource() != null ? input.geoSource() : GeoSource.COMMUNITY)
        .osmRef(input.osmRef())
        .status(status)
        .createdByUserId(user.getId())
        .build();

    try {
      return storeRepository.saveAndFlush(store);
    } catch (DataIntegrityViolationException e) {
      throw duplicateOf(input, e);
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

  private DuplicateException duplicateOf(CreateStoreInput input, DataIntegrityViolationException cause) {
    // Vlastní transakce (DuplicateLookupService) — po chybě uq_store_identity je aktuální DB
    // transakce na úrovni Postgresu "aborted", dotaz nad stejnou session by spadl.
    List<Store> similar = duplicateLookupService.findSimilarStores(input.name().trim(), input.city().trim());
    Long existingId = similar.isEmpty() ? null : similar.get(0).getId();
    return new DuplicateException(
        "Obchod s tímto názvem a městem už v katalogu existuje", existingId);
  }

  private String normalizeIco(String ico) {
    return blankToNull(ico);
  }

  private String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
