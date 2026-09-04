package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.Store;
import cz.kvalitacena.db.repo.ProductRepository;
import cz.kvalitacena.db.repo.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Dohledání "podobného" záznamu PO neúspěšném uložení kvůli {@code uq_store_identity}/
 * {@code uq_product_generic_name} (StoreService, ProductCatalogService). Musí běžet ve VLASTNÍ
 * transakci ({@code REQUIRES_NEW}) — po chybě unique indexu je aktuální DB transakce na úrovni
 * Postgresu "aborted" (každý další příkaz v ní skončí "current transaction is aborted"), takže
 * dotaz nad stejnou transakcí/session by spadl na {@code AssertionFailure} v Hibernate, ne na
 * smysluplnou odpověď. Samostatná transakce běží na jiném DB spojení a poškozené (rollback-only)
 * transakce volajícího se vůbec netýká.
 */
@Service
@RequiredArgsConstructor
public class DuplicateLookupService {

  private final StoreRepository storeRepository;
  private final ProductRepository productRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<Store> findSimilarStores(String name, String city) {
    return storeRepository.findSimilar(name, city);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public List<Product> findSimilarProducts(String name, Long storeId) {
    return productRepository.findSimilarByName(name, storeId, 5);
  }
}
