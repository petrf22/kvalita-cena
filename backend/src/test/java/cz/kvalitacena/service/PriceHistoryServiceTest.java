package cz.kvalitacena.service;

import cz.kvalitacena.config.I18nProperties;
import cz.kvalitacena.config.PriceHistoryProperties;
import cz.kvalitacena.controller.PriceHistory;
import cz.kvalitacena.db.repo.PriceDailyRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import cz.kvalitacena.service.fx.FxRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Přepočet zobrazovací měny v grafu (docs/lokalizace.md) — jádro je, že se KAŽDÝ bod přepočítá
 * kurzem PLATNÝM K JEHO DNI, ne dnešním, jinak by graf v USD mísil pohyb ceny s pohybem kurzu.
 */
@ExtendWith(MockitoExtension.class)
class PriceHistoryServiceTest {

  private static final Long PRODUCT_ID = 1L;
  private static final LocalDate DAY_1 = LocalDate.of(2026, 8, 3);
  private static final LocalDate DAY_2 = LocalDate.of(2026, 8, 4);

  @Mock
  private PriceDailyRepository priceDailyRepository;
  @Mock
  private StoreRepository storeRepository;
  @Mock
  private FxRateService fxRateService;

  private PriceHistoryService service;

  @BeforeEach
  void setUp() {
    PriceHistoryProperties properties = new PriceHistoryProperties();
    properties.setMaxDays(365);
    properties.setAnonymousMaxDays(90);
    I18nProperties i18n = new I18nProperties();
    i18n.setDefaultCountry("CZ");
    i18n.setCountryCurrency(Map.of("CZ", "CZK", "SK", "EUR", "PL", "PLN"));
    CurrencyResolver currencyResolver = new CurrencyResolver(i18n);
    service = new PriceHistoryService(priceDailyRepository, storeRepository, properties, currencyResolver,
        fxRateService, TestMessages.instance());
  }

  @Test
  void withoutDisplayCurrencyPointsHaveNoConvertedValues() {
    when(priceDailyRepository.dominantCurrency(eq(PRODUCT_ID), eq("REGULAR"), any())).thenReturn(Optional.of("CZK"));
    PriceDailyRepository.PricePointRow r1 = row(DAY_1, new BigDecimal("30.00"), new BigDecimal("30.00"), 5, 2);
    when(priceDailyRepository.nationalHistory(eq(PRODUCT_ID), eq("REGULAR"), eq("CZK"), any())).thenReturn(List.of(r1));

    PriceHistory history = service.history(PRODUCT_ID, null, null, 30, null, true, null);

    assertThat(history.currency()).isEqualTo("CZK");
    assertThat(history.displayCurrency()).isNull();
    assertThat(history.rateAttribution()).isNull();
    assertThat(history.points().get(0).convertedUnitPrice()).isNull();
    assertThat(history.points().get(0).convertedPriceAmount()).isNull();
  }

  @Test
  void displayCurrencyEqualToSeriesCurrencyConvertsNothing() {
    when(priceDailyRepository.dominantCurrency(eq(PRODUCT_ID), eq("REGULAR"), any())).thenReturn(Optional.of("CZK"));
    PriceDailyRepository.PricePointRow r1 = row(DAY_1, BigDecimal.TEN, BigDecimal.TEN, 1, 1);
    when(priceDailyRepository.nationalHistory(eq(PRODUCT_ID), eq("REGULAR"), eq("CZK"), any())).thenReturn(List.of(r1));

    PriceHistory history = service.history(PRODUCT_ID, null, null, 30, null, true, "CZK");

    assertThat(history.displayCurrency()).isNull();
    assertThat(history.points().get(0).convertedUnitPrice()).isNull();
  }

  /** Každý bod svým vlastním denním kurzem — dva dny musí použít dva různé kurzy (ne dnešní jeden pro oba). */
  @Test
  void eachPointConvertsWithItsOwnDaysRate() {
    when(priceDailyRepository.dominantCurrency(eq(PRODUCT_ID), eq("REGULAR"), any())).thenReturn(Optional.of("CZK"));
    PriceDailyRepository.PricePointRow r1 = row(DAY_1, new BigDecimal("30.00"), new BigDecimal("30.00"), 5, 2);
    PriceDailyRepository.PricePointRow r2 = row(DAY_2, new BigDecimal("32.00"), new BigDecimal("32.00"), 4, 2);
    when(priceDailyRepository.nationalHistory(eq(PRODUCT_ID), eq("REGULAR"), eq("CZK"), any()))
        .thenReturn(List.of(r1, r2));
    when(fxRateService.convert(new BigDecimal("30.00"), "CZK", "USD", DAY_1))
        .thenReturn(Optional.of(new FxRateService.Converted(new BigDecimal("1.30"), "USD", DAY_1)));
    when(fxRateService.convert(new BigDecimal("32.00"), "CZK", "USD", DAY_2))
        .thenReturn(Optional.of(new FxRateService.Converted(new BigDecimal("1.45"), "USD", DAY_2)));

    PriceHistory history = service.history(PRODUCT_ID, null, null, 30, null, true, "USD");

    assertThat(history.displayCurrency()).isEqualTo("USD");
    assertThat(history.rateAttribution()).isNotBlank();
    assertThat(history.points().get(0).convertedUnitPrice()).isEqualByComparingTo("1.30");
    assertThat(history.points().get(1).convertedUnitPrice()).isEqualByComparingTo("1.45");
    // Dva různé kurzy pro dva dny — kdyby appka omylem použila jeden (dnešní) kurz, obě
    // hodnoty by vyšly ve stejném poměru k unitPrice, což by tenhle test odhalil.
    assertThat(history.points().get(0).convertedUnitPrice())
        .isNotEqualByComparingTo(history.points().get(1).convertedUnitPrice());
  }

  private PriceDailyRepository.PricePointRow row(LocalDate day, BigDecimal unitPrice, BigDecimal priceAmount,
      int nObs, int storeCount) {
    PriceDailyRepository.PricePointRow row = org.mockito.Mockito.mock(PriceDailyRepository.PricePointRow.class);
    when(row.getDay()).thenReturn(day);
    when(row.getUnitPrice()).thenReturn(unitPrice);
    when(row.getPriceAmount()).thenReturn(priceAmount);
    when(row.getNObs()).thenReturn(nObs);
    when(row.getStoreCount()).thenReturn(storeCount);
    return row;
  }
}
