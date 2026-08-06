package cz.kvalitacena.db.repo;

public record ProductSearchCriteria(
    String query,
    Long storeId,
    String city,
    ProductSort sort,
    int first,
    int offset) {
}
