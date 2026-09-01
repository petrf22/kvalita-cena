package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.CatalogEditService;
import cz.kvalitacena.service.ChainCatalogService;
import cz.kvalitacena.service.CompanyRegistries;
import cz.kvalitacena.service.Coordinates;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.GeocodingService;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.StoreOverlayService;
import cz.kvalitacena.service.StoreSearchService;
import cz.kvalitacena.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class StoreGraphQlController {

  private static final double MAX_RADIUS_KM = 25;

  private final StoreRepository storeRepository;
  private final StoreSearchService storeSearchService;
  private final StoreService storeService;
  private final StoreOverlayService storeOverlayService;
  private final CatalogEditService catalogEditService;
  private final MediaService mediaService;
  private final ViewerContextResolver viewerContextResolver;
  private final GeocodingService geocodingService;
  private final CompanyRegistries companyRegistries;
  private final CountryResolver countryResolver;
  private final ChainCatalogService chainCatalogService;

  @QueryMapping
  public List<Store> nearbyStores(@Argument double lat, @Argument double lon, @Argument Double radiusKm,
      Authentication authentication) {
    double radius = Math.min(radiusKm == null ? 3 : radiusKm, MAX_RADIUS_KM);
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    // Zaokrouhlení na 3 desetinná místa (~110 m, docs/soukromi.md — dřív dokumentované, ale
    // nikde neimplementované) — při rádiusu 3–25 km je to pod rozlišovací schopnost výsledku,
    // ale snižuje přesnost polohy, která skončí v logu requestu/dotazu.
    double roundedLat = Coordinates.round(lat, 3);
    double roundedLon = Coordinates.round(lon, 3);
    return storeOverlayService.applyOverlay(
        storeRepository.findNearby(roundedLat, roundedLon, radius * 1000, viewerId), viewerId);
  }

  @QueryMapping
  public StoreSearchResult searchStores(@Argument String query, @Argument String city,
      @Argument Integer first, @Argument Integer offset, Authentication authentication) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    return storeSearchService.search(query, city, first, offset, viewerId);
  }

  @QueryMapping
  public List<RetailChain> chains(@Argument String query, @Argument String country, @Argument Integer first,
      Authentication authentication) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    return chainCatalogService.search(query, country, first, viewerId);
  }

  @QueryMapping
  public Store store(@Argument Long id, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    return storeRepository.findById(id)
        .filter(s -> isVisible(s, viewer))
        .map(s -> storeOverlayService.applyOverlay(s, viewer.userId()))
        .orElse(null);
  }

  /**
   * Viditelnost pod prahem důvěry (docs/reputace.md) — stejný predikát jako u produktu
   * (ProductGraphQlController.isVisible): ACTIVE vidí každý, vlastní PENDING jen autor,
   * skryté (nahlášené) jen autor. Nikdy FORBIDDEN, aby existenci skrytého záznamu nešlo
   * zvenčí odvodit (CLAUDE.md).
   */
  private boolean isVisible(Store store, ViewerContext viewer) {
    boolean ownerOrActive = store.getStatus() == StoreStatus.ACTIVE
        || (store.getStatus() == StoreStatus.PENDING && sameUser(store.getCreatedByUserId(), viewer))
        || viewer.moderator();
    if (!ownerOrActive) return false;
    return store.getHiddenAt() == null || sameUser(store.getCreatedByUserId(), viewer) || viewer.moderator();
  }

  private boolean sameUser(Long createdByUserId, ViewerContext viewer) {
    return createdByUserId != null && createdByUserId.equals(viewer.userId());
  }

  @MutationMapping
  public Store createStore(@Argument CreateStoreInput input, Authentication authentication) {
    return storeService.create(input, viewerContextResolver.resolve(authentication).publicUid());
  }

  @MutationMapping
  public Store updateStore(@Argument Long id, @Argument UpdateStoreInput input, Authentication authentication) {
    return catalogEditService.updateStore(id, input, viewerContextResolver.resolve(authentication).publicUid());
  }

  /**
   * {@code country} nemá ve schématu literální default "CZ" — server dosadí zemi přihlášeného
   * uživatele, jinak app.i18n.default-country (CountryResolver, docs/lokalizace.md).
   */
  @QueryMapping
  public GeocodeResult geocodeAddress(@Argument String street, @Argument String city,
      @Argument String postalCode, @Argument String country, Authentication authentication) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    return geocodingService.geocode(street, city, postalCode, countryResolver.resolve(country, viewerId));
  }

  @QueryMapping
  public CompanyInfo companyByIco(@Argument String ico, @Argument String country, Authentication authentication) {
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    String resolvedCountry = countryResolver.resolve(country, viewerId);
    return companyRegistries.forCountry(resolvedCountry).map(registry -> registry.lookup(ico)).orElse(null);
  }

  @QueryMapping
  public ReverseGeocodeResult reverseGeocode(@Argument double lat, @Argument double lon) {
    return geocodingService.reverseGeocode(lat, lon);
  }

  @BatchMapping(typeName = "Store", field = "photos")
  public Map<Store, List<Photo>> photos(List<Store> stores, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    List<Long> storeIds = stores.stream().map(Store::getId).toList();
    Map<Long, List<Photo>> byStore = mediaService.photosForBatch(RecordType.STORE, storeIds, viewer);
    Map<Store, List<Photo>> result = new LinkedHashMap<>();
    for (Store s : stores) {
      result.put(s, byStore.getOrDefault(s.getId(), List.of()));
    }
    return result;
  }
}
