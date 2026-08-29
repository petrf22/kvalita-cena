package cz.kvalitacena.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.kvalitacena.config.OpenFoodFactsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Úzký adaptér nad OFF API v3; ven nepouští transportní DTO ani technické výjimky. */
@Component
@RequiredArgsConstructor
public class OpenFoodFactsApiClient {

  private static final String FIELDS = String.join(",",
      "product_name", "brands", "product_quantity", "product_quantity_unit", "categories_tags",
      "image_front_url", "image_front_small_url", "additives_tags", "rev", "last_modified_t");

  private final OpenFoodFactsProperties properties;
  private volatile RestClient restClient;

  private synchronized RestClient restClient() {
    if (restClient == null) {
      HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
      var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
      requestFactory.setReadTimeout(properties.getTimeout());
      restClient = RestClient.builder()
          .baseUrl(properties.getBaseUrl())
          .requestFactory(requestFactory)
          .defaultHeader("User-Agent", properties.getUserAgent())
          .build();
    }
    return restClient;
  }

  /** Prázdný Optional znamená platné 404; ostatní chyby jsou dočasná nedostupnost. */
  public Optional<OffRemoteProduct> fetch(String ean) {
    try {
      ApiResponse response = restClient().get()
          .uri(uriBuilder -> uriBuilder.path("/api/v3/product/{code}")
              .queryParam("fields", FIELDS).build(ean))
          .retrieve()
          .onStatus(HttpStatusCode::is5xxServerError, (request, result) -> {
            throw new RestClientException("OFF returned " + result.getStatusCode());
          })
          .body(ApiResponse.class);
      if (response == null || response.product() == null) return Optional.empty();
      ApiProduct product = response.product();
      return Optional.of(new OffRemoteProduct(
          clean(product.productName(), 300), firstBrand(product.brands()), product.productQuantity(),
          supportedUnit(product.productQuantityUnit()), safeTags(product.categoryTags()),
          safeImageUrl(product.imageFrontUrl()), safeImageUrl(product.imageFrontSmallUrl()),
          safeTags(product.additivesTags()),
          product.revision(), epoch(product.lastModifiedEpoch())));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private String firstBrand(String brands) {
    if (brands == null) return null;
    return clean(brands.split(",", 2)[0], 200);
  }

  private String supportedUnit(String unit) {
    if (unit == null) return null;
    String normalized = unit.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "G", "KG", "ML", "L" -> normalized;
      default -> null;
    };
  }

  private List<String> safeTags(List<String> tags) {
    if (tags == null) return List.of();
    return tags.stream().filter(t -> t != null && t.matches("[a-z]{2}:[a-z0-9-]+"))
        .distinct().limit(100).toList();
  }

  private String safeImageUrl(String value) {
    String cleaned = clean(value, 1000);
    if (cleaned == null) return null;
    try {
      URI uri = URI.create(cleaned);
      String host = uri.getHost();
      if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
          || !(host.equals("openfoodfacts.org") || host.endsWith(".openfoodfacts.org"))) return null;
      return uri.toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private String clean(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    String trimmed = value.trim();
    return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
  }

  private OffsetDateTime epoch(Long seconds) {
    return seconds == null ? null : OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ApiResponse(@JsonProperty("product") ApiProduct product) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ApiProduct(
      @JsonProperty("product_name") String productName,
      @JsonProperty("brands") String brands,
      @JsonProperty("product_quantity") BigDecimal productQuantity,
      @JsonProperty("product_quantity_unit") String productQuantityUnit,
      @JsonProperty("categories_tags") List<String> categoryTags,
      @JsonProperty("image_front_url") String imageFrontUrl,
      @JsonProperty("image_front_small_url") String imageFrontSmallUrl,
      @JsonProperty("additives_tags") List<String> additivesTags,
      @JsonProperty("rev") Long revision,
      @JsonProperty("last_modified_t") Long lastModifiedEpoch) {
  }
}
