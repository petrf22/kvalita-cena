package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.RecordType;
import cz.kvalitacena.db.entity.RetailChain;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreStatus;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.exception.ErrorCode;
import cz.kvalitacena.exception.UnauthorizedException;
import cz.kvalitacena.security.ViewerContext;
import cz.kvalitacena.security.ViewerContextResolver;
import cz.kvalitacena.service.CatalogEditService;
import cz.kvalitacena.service.ChainCatalogService;
import cz.kvalitacena.service.CompanyRegistries;
import cz.kvalitacena.service.Coordinates;
import cz.kvalitacena.service.CountryResolver;
import cz.kvalitacena.service.GeocodingService;
import cz.kvalitacena.service.MediaService;
import cz.kvalitacena.service.OsmStoreCandidateFilter;
import cz.kvalitacena.service.OverpassPoiService;
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
  private static final double MIN_RADIUS_KM = 0.05;

  /**
   * Kolik metrů se přidá k hledanému okruhu jako kompenzace za zaokrouhlení polohy na 3
   * desetinná místa. Mřížka 0,001° je 111 m na šířce a ~71 m na délce (50° s. š.), takže se
   * střed hledání může posunout až o ~66 m — u okruhu 200 m třetina. Bez kompenzace by
   * uživateli vypadl obchod, ve kterém právě stojí. Klient seznam dofiltruje podle skutečné
   * vzdálenosti (docs/soukromi.md), takže navíc přišlých pár řádků nikdo neuvidí.
   */
  private static final double ROUNDING_SLACK_M = 70;

  private final StoreRepository storeRepository;
  private final StoreSearchService storeSearchService;
  private final StoreService storeService;
  private final StoreOverlayService storeOverlayService;
  private final CatalogEditService catalogEditService;
  private final MediaService mediaService;
  private final ViewerContextResolver viewerContextResolver;
  private final GeocodingService geocodingService;
  private final OverpassPoiService overpassPoiService;
  private final CompanyRegistries companyRegistries;
  private final CountryResolver countryResolver;
  private final ChainCatalogService chainCatalogService;

  @QueryMapping
  public List<Store> nearbyStores(@Argument double lat, @Argument double lon, @Argument Double radiusKm,
      Authentication authentication) {
    double radius = clampRadiusKm(radiusKm == null ? 3 : radiusKm);
    Long viewerId = viewerContextResolver.resolve(authentication).userId();
    // Zaokrouhlení na 3 desetinná místa (~110 m, docs/soukromi.md — dřív dokumentované, ale
    // nikde neimplementované) snižuje přesnost polohy, která skončí v logu requestu/dotazu.
    double roundedLat = Coordinates.round(lat, 3);
    double roundedLon = Coordinates.round(lon, 3);
    return storeOverlayService.applyOverlay(
        storeRepository.findNearby(roundedLat, roundedLon, radius * 1000 + ROUNDING_SLACK_M, viewerId),
        viewerId);
  }

  /**
   * Nabídka provozoven z OpenStreetMap — poslední záchrana, když appka v okolí žádný svůj
   * obchod nezná. Kandidáty, které v katalogu už jsou, vyřadí {@link OsmStoreCandidateFilter}
   * ještě před odesláním, ať uživatel nezakládá duplicitu.
   */
  @QueryMapping
  public OsmStoreCandidateResult nearbyOsmStores(@Argument double lat, @Argument double lon,
      @Argument Double radiusKm, Authentication authentication) {
    ViewerContext viewer = viewerContextResolver.resolve(authentication);
    if (viewer.userId() == null) {
      throw new UnauthorizedException(ErrorCode.STORE_CREATE_REQUIRES_LOGIN);
    }
    double radius = clampRadiusKm(radiusKm == null ? 0.5 : radiusKm);
    double roundedLat = Coordinates.round(lat, 3);
    double roundedLon = Coordinates.round(lon, 3);

    OsmStoreCandidateResult result = overpassPoiService.nearby(roundedLat, roundedLon, radius, ROUNDING_SLACK_M);
    if (result.candidates().isEmpty()) {
      return result;
    }
    // Vlastní obchody se berou ze stejného okruhu jako nabídka, aby se poznala shoda i u
    // provozovny, kterou někdo založil ručně (a osm_ref tedy nemá).
    List<Store> known = storeRepository.findNearby(roundedLat, roundedLon,
        radius * 1000 + ROUNDING_SLACK_M, viewer.userId());
    String fallbackCountry = countryResolver.resolve(null, viewer.userId());
    return new OsmStoreCandidateResult(result.status(),
        OsmStoreCandidateFilter.withoutKnownStores(result.candidates(), known, fallbackCountry),
        result.attribution());
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

  /** Strop kvůli ceně dotazu, spodní mez proto, že radiusKm = 0 je tichý nesmysl. */
  private double clampRadiusKm(double radiusKm) {
    return Math.min(Math.max(radiusKm, MIN_RADIUS_KM), MAX_RADIUS_KM);
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
