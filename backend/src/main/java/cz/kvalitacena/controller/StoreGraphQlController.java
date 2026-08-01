package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class StoreGraphQlController {

  private static final double MAX_RADIUS_KM = 25;

  private final StoreRepository storeRepository;

  @QueryMapping
  public List<Store> nearbyStores(@Argument double lat, @Argument double lon, @Argument Double radiusKm) {
    double radius = Math.min(radiusKm == null ? 3 : radiusKm, MAX_RADIUS_KM);
    return storeRepository.findNearby(lat, lon, radius * 1000);
  }
}
