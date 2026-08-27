package cz.kvalitacena.db.repo;

public record ProductSearchCriteria(
    String query,
    // GTIN-14 normalizace dotazu (ProductSearchService), NULL když dotaz nevypadá jako čárový
    // kód — appka tak tlačítkem "Hledat ceny tohoto zboží" najde zboží podle EANu, ne jen podle
    // názvu (viz ProductSearchRepositoryImpl, matched CTE).
    String codeQuery,
    Long storeId,
    String city,
    // Explicitní filtr kategorie z UI — VČETNĚ podstromu (ProductSearchRepositoryImpl,
    // sel_scope). NULL = bez filtru. Existenci ověřuje ProductSearchService, ne SQL: neplatné
    // id z rozejitého klienta má skončit CATEGORY_NOT_FOUND, ne tichým "nic jsme nenašli".
    Long categoryId,
    // Nikdy null v praxi (ProductGraphQlController.resolveCountry vždy dosadí konkrétní
    // hodnotu) — bez ní by ProductSort.PRICE_ASC řadilo napříč měnami (docs/lokalizace.md).
    String country,
    ProductSort sort,
    int first,
    int offset,
    // Kdo se ptá — rozhoduje o viditelnosti vlastního DRAFT zboží a o tom, čí patch
    // (core.product_user_edit) se promítne do zobrazeného názvu. NULL pro anonyma.
    Long viewerId,
    // Jazyk requestu (LocaleContextHolder), TÝŽ zdroj jako @BatchMapping Category.name —
    // kategorie se musí matchovat pod tím názvem, který uživatel v seznamu skutečně uvidí.
    String locale) {
}
