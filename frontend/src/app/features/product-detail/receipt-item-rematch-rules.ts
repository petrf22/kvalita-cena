import type { Product, ProductSummary } from '../../models/catalog';
import { ProductScope } from '../../models/generated/enums';
import { mergeUnitsFit, type UnitSource } from '../../shared/merge-units';

/**
 * Pravidla dopárování „zboží z účtenky" bez UI (`receipt-item-rematch.ts` z nich jen čte).
 * Zdroj pravdy je server (`ReceiptItemRematchService`); klient je opakuje jen proto, aby
 * nenabízel zboží, které by server odmítl s `RECEIPT_ITEM_REMATCH_INVALID`.
 */

type ScopeSource = Pick<ProductSummary, 'catalogScope' | 'scopeChain' | 'scopeStore'>;

/**
 * Cíl musí jít koupit tam, kde má zboží z účtenky ceny (ReceiptItemRematchService.scopeFits):
 * globální zboží vždy, lokální jen ze stejného řetězce, resp. téže provozovny. Stačí porovnat
 * rozsahy: bezkódové zboží z účtenky je lokální, zboží z účtenky s EAN globální — to pustí jen
 * globální cíl.
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

/**
 * Nabídka cílů: bez zboží z účtenky samotného, bez zboží, které se v jeho obchodě neprodává,
 * a bez zboží, na jehož jednotku ceny převést nejdou (`mergeUnitsFit`).
 */
export function rematchCandidates<T extends ScopeSource & UnitSource & { id: string }>(
  source: ScopeSource & UnitSource & { id: string },
  products: readonly T[],
): T[] {
  return products.filter(
    (product) =>
      product.id !== source.id &&
      rematchTargetFits(source, product) &&
      mergeUnitsFit(source, product),
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

/**
 * Výchozí dotaz dopárování z názvu zboží z účtenky: bez tokenů s číslicí („500G", „1,5L", „6X")
 * a bez interpunkce zkratek („TUK.ROHLIK"). Gramáž v dotazu sama přeskočí práh podobnosti
 * a vytáhne zboží, se kterým má společné jen balení („KECUP JEMNY 500G" nabízel těstoviny
 * 500g). Stejné pravidlo jako automatický odhad na serveru (`ReceiptMatchingService.fuzzyQuery`).
 * Když po očištění nic nezbude, zůstane název, jak je.
 */
export function rematchInitialQuery(name: string): string {
  const words = name
    .split(/\s+/)
    .filter((token) => !/\d/.test(token))
    .join(' ')
    .replace(/[^\p{L}\p{N}]+/gu, ' ')
    .trim();
  return words || name.trim();
}

/** Kde se zboží z účtenky prodává — název řetězce nebo provozovny pro vysvětlující větu. */
export function rematchScopeName(product: ScopeSource): string | null {
  if (product.catalogScope === ProductScope.Store) return product.scopeStore?.name ?? null;
  return product.scopeChain?.name ?? null;
}
