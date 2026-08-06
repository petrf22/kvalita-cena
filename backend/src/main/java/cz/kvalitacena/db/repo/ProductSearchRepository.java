package cz.kvalitacena.db.repo;

import java.util.List;

/**
 * Fragment k {@link ProductRepository} — hledání s filtrem obchod/město a agregáty
 * (počet hlášení, nejlevnější cena, kvalita) spočítanými JEDNÍM dotazem, aby respektovaly
 * zvolený filtr. Implementace {@link ProductSearchRepositoryImpl} nativním SQL, protože
 * potřebuje CTE a whitelistované dynamické ORDER BY, což JPQL/derived queries neumí.
 */
public interface ProductSearchRepository {

  List<ProductSearchRow> search(ProductSearchCriteria criteria);

  long count(ProductSearchCriteria criteria);
}
