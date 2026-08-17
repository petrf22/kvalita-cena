package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceKind;
import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Store;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Vlastní zapsaná cena ve výpisu "Moje příspěvky" — na rozdíl od {@link MyPrice} (poslední
 * zápis na kombinaci produkt/obchod/druh ceny, pro kartu "Vaše cena" na detailu produktu) je
 * tohle plný seznam všech vlastních zápisů, se stavem zveřejnění zděděným od blokujícího
 * katalogového záznamu (viz {@link cz.kvalitacena.service.MyContributionsService}).
 */
public record MyObservationItem(Product product, Store store, PriceKind priceKind, BigDecimal priceAmount,
                                  BigDecimal unitPrice, String currency, ConvertedPrice converted,
                                  OffsetDateTime observedAt, OffsetDateTime createdAt, PublicationStatus publication) {
}
