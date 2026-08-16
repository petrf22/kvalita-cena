package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.GeoSource;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.entity.StoreUserEdit;
import cz.kvalitacena.db.repo.RetailChainRepository;
import cz.kvalitacena.db.repo.StoreUserEditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Obdoba {@link ProductOverlayService} pro core.store — {@code lat}/{@code lon} v patchi smí
 * ovlivnit jen ZOBRAZENOU hodnotu, filtr vzdálenosti v {@code nearbyStores} dál pracuje jen
 * s globálními souřadnicemi (idx_store_geo), viz {@code StoreRepository.findNearby}.
 */
@Service
@RequiredArgsConstructor
public class StoreOverlayService {

  private final StoreUserEditRepository storeUserEditRepository;
  private final RetailChainRepository retailChainRepository;

  public Store applyOverlay(Store store, Long viewerId) {
    if (store == null || viewerId == null) return store;
    return storeUserEditRepository.findByStoreIdAndUserId(store.getId(), viewerId)
        .map(edit -> merge(store, edit))
        .orElse(store);
  }

  public List<Store> applyOverlay(List<Store> stores, Long viewerId) {
    if (viewerId == null || stores.isEmpty()) return stores;
    List<Long> ids = stores.stream().map(Store::getId).toList();
    Map<Long, StoreUserEdit> edits = storeUserEditRepository
        .findByStoreIdInAndUserId(ids, viewerId).stream()
        .collect(Collectors.toMap(StoreUserEdit::getStoreId, Function.identity()));
    if (edits.isEmpty()) return stores;
    return stores.stream()
        .map(s -> edits.containsKey(s.getId()) ? merge(s, edits.get(s.getId())) : s)
        .toList();
  }

  private Store merge(Store store, StoreUserEdit edit) {
    Store.StoreBuilder builder = store.toBuilder();
    if (edit.getName() != null) builder.name(edit.getName());
    if (edit.getChainId() != null) {
      retailChainRepository.findById(edit.getChainId()).ifPresent(builder::chain);
    } else if (edit.getClearedFields().contains("chain")) {
      builder.chain(null);
    }
    if (edit.getStreet() != null) builder.street(edit.getStreet());
    else if (edit.getClearedFields().contains("street")) builder.street(null);
    if (edit.getCity() != null) builder.city(edit.getCity());
    if (edit.getPostalCode() != null) builder.postalCode(edit.getPostalCode());
    else if (edit.getClearedFields().contains("postalCode")) builder.postalCode(null);
    // country tu záměrně chybí — updateStore ji zapisuje přímo do globální entity
    // (viz StoreUserEdit, CatalogEditService.updateStore), overlay nikdy nepatchuje.
    if (edit.getIco() != null) builder.ico(edit.getIco());
    else if (edit.getClearedFields().contains("ico")) builder.ico(null);
    if (edit.getLat() != null) builder.lat(edit.getLat());
    if (edit.getLon() != null) builder.lon(edit.getLon());
    if (edit.getGeoSource() != null) builder.geoSource(GeoSource.valueOf(edit.getGeoSource()));
    if (edit.getOsmRef() != null) builder.osmRef(edit.getOsmRef());
    builder.editedByMe(true);
    return builder.build();
  }
}
