package cz.kvalitacena.service;

import cz.kvalitacena.db.entity.Product;
import cz.kvalitacena.db.entity.ProductScope;
import cz.kvalitacena.db.entity.Store;
import org.springframework.stereotype.Service;

/** Jediný zdroj pravidel, ve které provozovně lze použít lokální bezkódový produkt. */
@Service
public class ProductScopeService {

  public void assignLocalScope(Product product, Store store) {
    if (store.getChain() != null) {
      product.setCatalogScope(ProductScope.CHAIN);
      product.setScopeChain(store.getChain());
      product.setScopeStore(null);
    } else {
      product.setCatalogScope(ProductScope.STORE);
      product.setScopeChain(null);
      product.setScopeStore(store);
    }
  }

  public boolean isAvailableAt(Product product, Store store) {
    return switch (product.getCatalogScope()) {
      case GLOBAL, LEGACY_GLOBAL -> true;
      case CHAIN -> product.getScopeChain() != null && store.getChain() != null
          && product.getScopeChain().getId().equals(store.getChain().getId());
      case STORE -> product.getScopeStore() != null
          && product.getScopeStore().getId().equals(store.getId());
    };
  }
}
