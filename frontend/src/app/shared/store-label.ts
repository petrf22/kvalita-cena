import { Store } from '../models/catalog';

/**
 * Popisek obchodu v seznamech/pickeru (název — město), s kódem země PŘILEPENÝM na konec jen
 * když se liší od domácí země vieweru ({@link
 * import('../services/country-service').CountryService}) — český obchod se českému uživateli
 * ukáže beze změny, slovenský dostane "(SK)" (docs/lokalizace.md, "Country selector v UI").
 * Mobilní protějšek: mobile ui/common/StoreLabel.kt (`homeCountry` parametr přidán stejně).
 */
export function storeLabel(
  store: Pick<Store, 'name' | 'city' | 'country'>,
  homeCountry: string | null | undefined,
): string {
  const base = `${store.name} — ${store.city}`;
  return store.country && store.country !== homeCountry ? `${base} (${store.country})` : base;
}
