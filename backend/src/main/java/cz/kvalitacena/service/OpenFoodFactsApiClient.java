package cz.kvalitacena.service;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.config.OpenFoodFactsProperties;
import cz.kvalitacena.db.entity.OffImageKind;
import cz.kvalitacena.db.entity.OffProductImage;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Úzký adaptér nad OFF API v3; ven nepouští transportní DTO ani technické výjimky. */
@Component
@RequiredArgsConstructor
public class OpenFoodFactsApiClient {

  private static final List<String> BASE_FIELDS = List.of(
      "product_name", "lang", "languages_tags", "brands", "product_quantity",
      "product_quantity_unit", "categories_tags", "image_front_url", "image_front_small_url",
      "selected_images", "additives_tags", "rev", "last_modified_t");

  private static final String LOCALIZED_NAME_PREFIX = "product_name_";

  /**
   * OFF používá {@code xx} jako „jazykově neutrální" (typicky název, který je stejný ve všech
   * jazycích). Není to jazyk, takže do mapy po jazycích nepatří — appka by ho nabízela jako
   * další jazyk k překladu. Neutrální hodnotu stejně nese počítané {@code product_name}, které
   * se ukládá zvlášť jako poslední článek fallbacku.
   */
  private static final String LANGUAGE_NEUTRAL = "xx";

  private final OpenFoodFactsProperties properties;
  private final I18nProperties i18nProperties;
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

  /**
   * Jazyky, o které se pro název žádá — odvozeno z {@code app.i18n.supported-locales}, ne
   * z vlastního seznamu: rozšíření appky o jazyk se tím promítne i sem (docs/lokalizace.md).
   * {@code fields} v OFF API nezná zástupný znak, takže vyjmenovat se musí.
   *
   * <p>Fotky ({@code selected_images}) se nevyjmenovávají — OFF je vrací rovnou jako mapu
   * všech jazyků, které pro produkt má.
   */
  public List<String> nameLocales() {
    List<String> locales = i18nProperties.getSupportedLocales();
    return locales == null ? List.of() : locales.stream().map(this::languageOf).distinct().sorted().toList();
  }

  /** Prázdný Optional znamená platné 404; ostatní chyby jsou dočasná nedostupnost. */
  public Optional<OffRemoteProduct> fetch(String ean) {
    List<String> nameLocales = nameLocales();
    String fields = fields(nameLocales);
    try {
      ApiResponse response = restClient().get()
          .uri(uriBuilder -> uriBuilder.path("/api/v3/product/{code}")
              .queryParam("fields", fields).build(ean))
          .retrieve()
          .onStatus(HttpStatusCode::is5xxServerError, (request, result) -> {
            throw new RestClientException("OFF returned " + result.getStatusCode());
          })
          .body(ApiResponse.class);
      if (response == null || response.product() == null) return Optional.empty();
      ApiProduct product = response.product();
      return Optional.of(new OffRemoteProduct(
          language(product.lang), clean(product.productName, 300), localizedNames(product),
          nameLocales, firstBrand(product.brands), product.productQuantity,
          supportedUnit(product.productQuantityUnit), safeTags(product.categoryTags),
          safeImageUrl(product.imageFrontUrl), safeImageUrl(product.imageFrontSmallUrl),
          images(product.selectedImages), safeTags(product.additivesTags),
          product.revision, epoch(product.lastModifiedEpoch)));
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
  }

  private String fields(List<String> nameLocales) {
    List<String> fields = new ArrayList<>(BASE_FIELDS);
    nameLocales.forEach(locale -> fields.add(LOCALIZED_NAME_PREFIX + locale));
    return String.join(",", fields);
  }

  /** {@code cs-CZ} i {@code cs} musí skončit na {@code cs} — OFF klíčuje dvoupísmenným kódem. */
  private String languageOf(String locale) {
    return Locale.forLanguageTag(locale).getLanguage();
  }

  private Map<String, String> localizedNames(ApiProduct product) {
    Map<String, String> names = new LinkedHashMap<>();
    product.localizedNames.forEach((lang, name) -> {
      String cleaned = clean(name, 300);
      if (cleaned != null) names.put(lang, cleaned);
    });
    return names;
  }

  /**
   * {@code selected_images} → ploché řádky pro {@code off.product_image}. Bere se jen
   * {@code front} a {@code ingredients} — {@code nutrition} appka nikde nezobrazuje, takže by
   * šlo o cizí data bez čtenáře. {@code small} je volitelný; bez {@code display} se řádek
   * zahodí, protože bez plné velikosti není co otevřít.
   */
  private List<OffProductImage> images(Map<String, ApiSelectedImage> selectedImages) {
    if (selectedImages == null) return List.of();
    List<OffProductImage> images = new ArrayList<>();
    for (OffImageKind kind : OffImageKind.values()) {
      ApiSelectedImage selected = selectedImages.get(kind.name().toLowerCase(Locale.ROOT));
      if (selected == null || selected.display() == null) continue;
      selected.display().forEach((lang, url) -> {
        String safeUrl = safeImageUrl(url);
        if (safeUrl == null || language(lang) == null) return;
        String small = selected.small() == null ? null : safeImageUrl(selected.small().get(lang));
        images.add(OffProductImage.builder()
            .kind(kind).lang(language(lang)).url(safeUrl).smallUrl(small).build());
      });
    }
    return images;
  }

  /** Kód jazyka jen v tom tvaru, který snese CHECK v off.* — jinak radši nic. */
  private String language(String value) {
    if (value == null) return null;
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return normalized.matches("[a-z]{2}") ? normalized : null;
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
  private record ApiSelectedImage(@JsonProperty("display") Map<String, String> display,
                                  @JsonProperty("small") Map<String, String> small) {
  }

  /**
   * Třída, ne record: {@code product_name_<lc>} má JMÉNO ZÁVISLÉ NA JAZYCE, takže se nedá
   * deklarovat staticky a musí ho posbírat {@link JsonAnySetter} (ten na record komponentách
   * není). Zbytek polí zůstává deklarovaný, ať se překlepy v názvu pole poznají.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static final class ApiProduct {
    @JsonProperty("product_name") String productName;
    @JsonProperty("lang") String lang;
    @JsonProperty("brands") String brands;
    @JsonProperty("product_quantity") BigDecimal productQuantity;
    @JsonProperty("product_quantity_unit") String productQuantityUnit;
    @JsonProperty("categories_tags") List<String> categoryTags;
    @JsonProperty("image_front_url") String imageFrontUrl;
    @JsonProperty("image_front_small_url") String imageFrontSmallUrl;
    @JsonProperty("selected_images") Map<String, ApiSelectedImage> selectedImages;
    @JsonProperty("additives_tags") List<String> additivesTags;
    @JsonProperty("rev") Long revision;
    @JsonProperty("last_modified_t") Long lastModifiedEpoch;

    final Map<String, String> localizedNames = new LinkedHashMap<>();

    @JsonAnySetter
    void other(String key, Object value) {
      if (!key.startsWith(LOCALIZED_NAME_PREFIX) || !(value instanceof String text)) return;
      String lang = key.substring(LOCALIZED_NAME_PREFIX.length()).toLowerCase(Locale.ROOT);
      // OFF vrací i varianty jako product_name_de_imported — do klíče patří jen holý jazyk.
      if (lang.matches("[a-z]{2}") && !lang.equals(LANGUAGE_NEUTRAL)) localizedNames.put(lang, text);
    }
  }
}
