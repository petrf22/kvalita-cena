package cz.kvalitacena.db.repo;

public record ProductSearchCriteria(
    String query,
    Long storeId,
    String city,
    ProductSort sort,
    int first,
    int offset,
    // Kdo se ptá — rozhoduje o viditelnosti vlastního DRAFT zboží a o tom, čí patch
    // (core.product_user_edit) se promítne do zobrazeného názvu. NULL pro anonyma.
    Long viewerId) {
}
