import type { Product, ProductSummary } from '../../models/catalog';
import { ProductScope } from '../../models/generated/enums';

/**
 * Pravidla dopárování „zboží z účtenky" bez UI (`receipt-item-rematch.ts` z nich jen čte).
 * Zdroj pravdy je server (`ReceiptItemRematchService`); klient je opakuje jen proto, aby
 * nenabízel zboží, které by server odmítl s `RECEIPT_ITEM_REMATCH_INVALID`.
 */

type ScopeSource = Pick<ProductSummary, 'catalogScope' | 'scopeChain' | 'scopeStore'>;

/**
 * Cíl musí jít koupit tam, kde má zboží z účtenky ceny (ReceiptItemRematchService.scopeFits):
 * globální zboží vždy, lokální jen ze stejného řetězce, resp. téže provozovny. Zboží z účtenky
 * má rozsah vždy lokální, takže stačí porovnat rozsahy.
 */
export function rematchTargetFits(source: ScopeSource, target: ScopeSource): boolean {
  switch (target.catalogScope) {
    case ProductScope.Global:
    case ProductScope.LegacyGlobal:
      return true;
    case ProductScope.Chain:
      return (
        source.catalogScope === ProductScope.Chain &&
        target.scopeChain != null &&
        target.scopeChain.id === source.scopeChain?.id
      );
    case ProductScope.Store:
      return (
        source.catalogScope === ProductScope.Store &&
        target.scopeStore != null &&
        target.scopeStore.id === source.scopeStore?.id
      );
    default:
      return false;
  }
}

/** Nabídka cílů: bez zboží z účtenky samotného a bez zboží, které se v jeho obchodě neprodává. */
export function rematchCandidates<T extends ScopeSource & { id: string }>(
  source: ScopeSource & { id: string },
  products: readonly T[],
): T[] {
  return products.filter(
    (product) => product.id !== source.id && rematchTargetFits(source, product),
  );
}

/**
 * Obchod pro `productSuggestions(name, storeId)` — omezí lokální zboží na rozsah zboží
 * z účtenky: u provozovny ona sama, u řetězce kterákoli jeho provozovna, kde má zboží cenu
 * (agregát nebo vlastní zápis). Bez obchodu se hledá napříč katalogem a to, co se v obchodě
 * neprodává, odfiltruje `rematchCandidates`.
 */
export function rematchStoreId(
  product: ScopeSource & Pick<Product, 'prices' | 'myPrices'>,
): string | null {
  if (product.catalogScope === ProductScope.Store) return product.scopeStore?.id ?? null;
  const chainId = product.scopeChain?.id;
  if (product.catalogScope !== ProductScope.Chain || !chainId) return null;
  const stores = [
    ...product.prices.map((p) => p.store),
    ...(product.myPrices ?? []).map((p) => p.store),
  ];
  return stores.find((store) => store.chain?.id === chainId)?.id ?? null;
}

/** Kde se zboží z účtenky prodává — název řetězce nebo provozovny pro vysvětlující větu. */
export function rematchScopeName(product: ScopeSource): string | null {
  if (product.catalogScope === ProductScope.Store) return product.scopeStore?.name ?? null;
  return product.scopeChain?.name ?? null;
}
