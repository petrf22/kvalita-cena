/* eslint-disable */
/** Internal type. DO NOT USE DIRECTLY. */
type Exact<T extends { [key: string]: unknown }> = { [K in keyof T]: T[K] };
/** Internal type. DO NOT USE DIRECTLY. */
export type Incremental<T> = T | { [P in keyof T]?: P extends ' $fragmentName' | '__typename' ? T[P] : never };
import type { DocumentTypeDecoration } from '@graphql-typed-document-node/core';
/** Publikum, kterému lze zpřístupnit konkrétní pole profilu. */
export type Audience =
  | 'FRIENDS'
  | 'PUBLIC';

export type ChainType =
  | 'CHAIN'
  | 'FARM_SHOP'
  | 'INDEPENDENT'
  | 'MARKET'
  | 'ONLINE';

export type Confidence =
  | 'HIGH'
  | 'LOW'
  | 'MEDIUM';

export type CreateProductInput = {
  /** Volný text značky — pokud neexistuje, založí se nová core.brand. */
  brandName?: string | null | undefined;
  categoryId: string;
  /** Naskenovaný EAN — bez něj vzniká bezkódová druhová položka (isGeneric=true, status=DRAFT). */
  code?: string | null | undefined;
  isVariableWeight?: boolean | null | undefined;
  name: string;
  netContentUom?: NetContentUom | null | undefined;
  netContentValue?: number | null | undefined;
  piecesInPack?: number | null | undefined;
  unitBase: UnitBase;
};

export type CreateStoreInput = {
  chainId?: string | null | undefined;
  city: string;
  /** Bez zadání server dosadí zemi vieweru, jinak app.i18n.default-country (CountryResolver, docs/lokalizace.md) — ŽÁDNÝ literální default tady, jinak by tahle větev nikdy nenaběhla. */
  country?: string | null | undefined;
  geoSource?: GeoSource | null | undefined;
  /** 8 číslic, ověří se v ARES (companyByIco) — viz docs/soukromi.md. */
  ico?: string | null | undefined;
  /** Volitelné — bez souřadnic obchod nenajde nearbyStores, jen searchStores. */
  lat?: number | null | undefined;
  lon?: number | null | undefined;
  name: string;
  osmRef?: string | null | undefined;
  postalCode?: string | null | undefined;
  street?: string | null | undefined;
};

export type ExternalLinkKind =
  | 'E_NUMBERS'
  | 'OPEN_FOOD_FACTS';

export type GeoSource =
  /** Souřadnice zadal/potvrdil uživatel (ručně, nebo výběrem geokódovaného kandidáta). */
  | 'COMMUNITY'
  /** Souřadnice převzaté z OpenStreetMap Nominatim — jen lat/lon a osm_ref, nic dalšího z OSM. */
  | 'OSM';

export type NetContentUom =
  | 'G'
  | 'KG'
  | 'L'
  | 'ML'
  | 'PCS';

export type ObservationStatus =
  | 'ACTIVE'
  | 'DISPUTED'
  | 'PENDING'
  | 'REJECTED';

export type PriceKind =
  | 'CLEARANCE'
  | 'CLUB_CARD'
  | 'MULTIBUY'
  | 'PROMO'
  | 'REGULAR';

export type ProductSort =
  | 'LAST_REPORTED'
  | 'NAME'
  | 'PRICE_ASC'
  /** 1 = nejlepší, tedy vzestupně. */
  | 'QUALITY'
  /** Výchozí — nejvíc potvrzené zboží nahoře (součet agg.price_current.n_obs). */
  | 'REPORT_COUNT';

export type ProductStatus =
  | 'ACTIVE'
  | 'DRAFT'
  | 'MERGED'
  | 'REJECTED';

/** Pole profilu, pro které lze zvlášť zapnout viditelnost vůči Audience. */
export type ProfileField =
  | 'AVATAR'
  | 'CONTACT_EMAIL'
  | 'DISPLAY_NAME'
  | 'FIRST_NAME'
  | 'LAST_NAME'
  | 'PHONE';

export type ProfileFieldAudienceInput = {
  audience: Audience;
  field: ProfileField;
};

/**
 * Viditelnost profilu (auth.user_profile.visibility) — výchozí ANONYMOUS, aby si lidé ze
 * setrvačnosti nedávali skutečné jméno (docs/soukromi.md). U PUBLIC/FRIENDS teprve rozhoduje
 * Profile.visibleFields, KTERÁ pole se komu zobrazí.
 */
export type ProfileVisibility =
  | 'ANONYMOUS'
  | 'FRIENDS'
  | 'PUBLIC';

/**
 * Kdy se vlastní záznam propaguje globálně (docs/datovy-model.md, "Uživatelská vrstva nad
 * globálními daty"; prahy v docs/reputace.md) — jeden zdroj pravdy pro "Moje příspěvky", ať
 * klient neumí zobrazit protichůdný text na dvou různých obrazovkách.
 */
export type PublicationState =
  /** DRAFT zboží / PENDING obchod — v hledání zatím vidí jen autor, dokud ho nepotvrdí jiní přispěvatelé. */
  | 'AWAITING_CONFIRMATIONS'
  /** hidden_at != null po nahlášení (core.record_flag) — čeká na přezkum, vidí ho dál jen autor. */
  | 'HIDDEN_AFTER_FLAGS'
  /** Patch v core.product_user_edit/core.store_user_edit — konsolidační job zatím neběží, vidí ho jen autor. */
  | 'PENDING_MERGE'
  /** Vidí každý (status ACTIVE). */
  | 'PUBLIC';

export type QuantityBasis =
  | 'PACKAGE'
  | 'PER_KG'
  | 'PER_L'
  | 'PER_PIECE';

export type RecordType =
  /** Nahlašovaný typ pro flagRecord — core.media samo nese jen PRODUCT/STORE (čí je fotka). */
  | 'PHOTO'
  | 'PRODUCT'
  | 'STORE';

export type SubmitObservationInput = {
  /**
   * ISO-4217 kód měny — chybí-li, dosadí se podle země obchodu (docs/lokalizace.md). Volitelný
   * jen kvůli příhraničním prodejnám, které občas cení v jiné měně, než je země provozovny;
   * server hodnotu validuje proti podporovaným měnám, neplatnou tiše ignoruje a dosadí zemi obchodu.
   */
  currency?: string | null | undefined;
  /** Povinné jen pro MULTIBUY (např. '3 za 50'). */
  multibuyQty?: number | null | undefined;
  multibuyTotal?: number | null | undefined;
  /** Kdy uživatel cenu viděl — chybí-li, použije se teď (docs/datovy-model.md, observed_at ≠ created_at). */
  observedAt?: string | null | undefined;
  priceAmount: number;
  priceKind?: PriceKind | null | undefined;
  productId: string;
  quantityBasis?: QuantityBasis | null | undefined;
  storeId: string;
};

export type UnitBase =
  | 'COUNT'
  | 'MASS'
  | 'VOLUME';

export type UpdateProductInput = {
  brandName?: string | null | undefined;
  categoryId?: string | null | undefined;
  /** True smaže značku (vrátí zboží na 'bez značky') — jinak se posílá brandName. */
  clearBrand?: boolean | null | undefined;
  clearPiecesInPack?: boolean | null | undefined;
  isVariableWeight?: boolean | null | undefined;
  name?: string | null | undefined;
  netContentUom?: NetContentUom | null | undefined;
  netContentValue?: number | null | undefined;
  piecesInPack?: number | null | undefined;
  unitBase?: UnitBase | null | undefined;
};

/**
 * Patch nad auth.user_profile (+ app_user.display_name) — null u pole znamená "nezměněno",
 * clearX = true maže hodnotu (stejný vzor jako UpdateStoreInput/UpdateProductInput).
 */
export type UpdateProfileInput = {
  clearContactEmail?: boolean | null | undefined;
  clearDisplayName?: boolean | null | undefined;
  clearFirstName?: boolean | null | undefined;
  clearLastName?: boolean | null | undefined;
  clearPhone?: boolean | null | undefined;
  contactEmail?: string | null | undefined;
  displayName?: string | null | undefined;
  firstName?: string | null | undefined;
  lastName?: string | null | undefined;
  phone?: string | null | undefined;
  visibility?: ProfileVisibility | null | undefined;
  /** null nechá matici beze změny, JAKÝKOLI seznam (i prázdný) ji celou nahradí. */
  visibleFields?: Array<ProfileFieldAudienceInput> | null | undefined;
};

export type UpdateStoreInput = {
  chainId?: string | null | undefined;
  city?: string | null | undefined;
  clearChain?: boolean | null | undefined;
  clearIco?: boolean | null | undefined;
  clearPostalCode?: boolean | null | undefined;
  clearStreet?: boolean | null | undefined;
  country?: string | null | undefined;
  geoSource?: GeoSource | null | undefined;
  /** 8 číslic, ověří se kontrolním součtem — viz docs/soukromi.md. */
  ico?: string | null | undefined;
  lat?: number | null | undefined;
  lon?: number | null | undefined;
  name?: string | null | undefined;
  osmRef?: string | null | undefined;
  postalCode?: string | null | undefined;
  street?: string | null | undefined;
};

export type CountriesQueryVariables = Exact<{ [key: string]: never; }>;


export type CountriesQuery = { countries: Array<{ code: string, currency: string, defaultLocale: string | null }> };

export type FxInfoQueryVariables = Exact<{ [key: string]: never; }>;


export type FxInfoQuery = { fxInfo: { displayCurrencies: Array<string>, latestRateDate: string | null, attribution: string } };

export type StoreFieldsFragment = { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null };

export type PhotoFieldsFragment = { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string };

export type ProfileFieldsFragment = { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string } | null };

export type StoreDetailFieldsFragment = { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, chain: { id: string, name: string, chainType: ChainType } | null };

export type ConvertedPriceFieldsFragment = { amount: number, currency: string, rateDate: string };

export type PriceCurrentFieldsFragment = { priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null };

export type ProductFieldsFragment = { id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> };

export type ProductDetailFieldsFragment = { gtin: string | null, myQualityRating: number | null, id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> };

export type ProductSummaryFieldsFragment = { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } };

export type PublicationStatusFieldsFragment = { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean };

export type SearchItemFieldsFragment = { observationCount: number, bestPrice: number | null, bestUnitPrice: number | null, currency: string | null, bestPriceObservations: number | null, lastObservedAt: string | null, qualityAverage: number | null, qualityCount: number, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } }, converted: { amount: number, currency: string, rateDate: string } | null, convertedUnit: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null };

export type UpdatePhotoMutationVariables = Exact<{
  id: string;
  caption?: string | null | undefined;
  sortOrder?: number | null | undefined;
}>;


export type UpdatePhotoMutation = { updatePhoto: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string } };

export type DeletePhotoMutationVariables = Exact<{
  id: string;
}>;


export type DeletePhotoMutation = { deletePhoto: boolean };

export type FlagPhotoMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagPhotoMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type MyProductsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyProductsQuery = { myProducts: { totalCount: number, items: Array<{ createdAt: string, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } } }> } };

export type MyStoresQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyStoresQuery = { myStores: { totalCount: number, items: Array<{ createdAt: string, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } }> } };

export type MyObservationsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyObservationsQuery = { myObservations: { totalCount: number, items: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, createdAt: string, converted: { amount: number, currency: string, rateDate: string } | null, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } }, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } }> } };

export type MyEditsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyEditsQuery = { myEdits: { totalCount: number, items: Array<{ recordType: RecordType, updatedAt: string, changedFields: Array<string>, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } } | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }> } };

export type SearchProductsQueryVariables = Exact<{
  query: string;
  storeId?: string | null | undefined;
  city?: string | null | undefined;
  country?: string | null | undefined;
  sort?: ProductSort | null | undefined;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type SearchProductsQuery = { searchProducts: { totalCount: number, hasMore: boolean, items: Array<{ observationCount: number, bestPrice: number | null, bestUnitPrice: number | null, currency: string | null, bestPriceObservations: number | null, lastObservedAt: string | null, qualityAverage: number | null, qualityCount: number, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } }, converted: { amount: number, currency: string, rateDate: string } | null, convertedUnit: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }> } };

export type SearchFacetsQueryVariables = Exact<{
  country?: string | null | undefined;
}>;


export type SearchFacetsQuery = { searchFacets: { cities: Array<string>, stores: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> } };

export type ProductQueryVariables = Exact<{
  id: string;
}>;


export type ProductQuery = { product: { gtin: string | null, myQualityRating: number | null, id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } | null };

export type ProductByCodeQueryVariables = Exact<{
  code: string;
}>;


export type ProductByCodeQuery = { productByCode: { gtin: string | null, myQualityRating: number | null, id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } | null };

export type ProductSuggestionsQueryVariables = Exact<{
  name: string;
  first?: number | null | undefined;
}>;


export type ProductSuggestionsQuery = { productSuggestions: Array<{ id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string } }> };

export type CategoriesQueryVariables = Exact<{ [key: string]: never; }>;


export type CategoriesQuery = { categories: Array<{ id: string, name: string, slug: string, path: string }> };

export type CreateProductMutationVariables = Exact<{
  input: CreateProductInput;
}>;


export type CreateProductMutation = { createProduct: { gtin: string | null, myQualityRating: number | null, id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } };

export type UpdateProductMutationVariables = Exact<{
  id: string;
  input: UpdateProductInput;
}>;


export type UpdateProductMutation = { updateProduct: { gtin: string | null, myQualityRating: number | null, id: string, name: string, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } };

export type FlagProductMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagProductMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type PriceHistoryQueryVariables = Exact<{
  productId: string;
  priceKind?: PriceKind | null | undefined;
  days?: number | null | undefined;
}>;


export type PriceHistoryQuery = { priceHistory: { priceKind: PriceKind, days: number, currency: string, displayCurrency: string | null, rateAttribution: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null, points: Array<{ day: string, priceAmount: number | null, unitPrice: number, nObs: number, storeCount: number, convertedUnitPrice: number | null, convertedPriceAmount: number | null }> } };

export type RateProductMutationVariables = Exact<{
  productId: string;
  grade: number;
}>;


export type RateProductMutation = { rateProduct: { average: number | null, count: number } };

export type SubmitObservationMutationVariables = Exact<{
  input: SubmitObservationInput;
}>;


export type SubmitObservationMutation = { submitObservation: { id: string, priceAmount: number, currency: string, unitPrice: number | null, priceKind: PriceKind, quantityBasis: QuantityBasis, observedAt: string, status: ObservationStatus } };

export type NearbyStoresQueryVariables = Exact<{
  lat: number;
  lon: number;
  radiusKm?: number | null | undefined;
}>;


export type NearbyStoresQuery = { nearbyStores: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> };

export type SearchStoresQueryVariables = Exact<{
  query?: string | null | undefined;
  city?: string | null | undefined;
  first?: number | null | undefined;
}>;


export type SearchStoresQuery = { searchStores: { totalCount: number, hasMore: boolean, items: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> } };

export type StoreQueryVariables = Exact<{
  id: string;
}>;


export type StoreQuery = { store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string }>, chain: { id: string, name: string, chainType: ChainType } | null } | null };

export type CreateStoreMutationVariables = Exact<{
  input: CreateStoreInput;
}>;


export type CreateStoreMutation = { createStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } };

export type UpdateStoreMutationVariables = Exact<{
  id: string;
  input: UpdateStoreInput;
}>;


export type UpdateStoreMutation = { updateStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } };

export type FlagStoreMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagStoreMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type GeocodeAddressQueryVariables = Exact<{
  street?: string | null | undefined;
  city: string;
  postalCode?: string | null | undefined;
}>;


export type GeocodeAddressQuery = { geocodeAddress: { attribution: string, candidates: Array<{ lat: number, lon: number, displayName: string, osmRef: string }> } };

export type ReverseGeocodeQueryVariables = Exact<{
  lat: number;
  lon: number;
}>;


export type ReverseGeocodeQuery = { reverseGeocode: { street: string | null, city: string | null, postalCode: string | null, country: string | null, osmRef: string | null, attribution: string } };

export type CompanyByIcoQueryVariables = Exact<{
  ico: string;
}>;


export type CompanyByIcoQuery = { companyByIco: { ico: string, name: string, street: string | null, city: string | null, postalCode: string | null } | null };

export type MeQueryVariables = Exact<{ [key: string]: never; }>;


export type MeQuery = { me: { publicHandle: string, displayName: string | null, createdAt: string, trusted: boolean, locale: string | null, country: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string } | null } } | null };

export type SetLocaleMutationVariables = Exact<{
  locale: string;
  country?: string | null | undefined;
}>;


export type SetLocaleMutation = { setLocale: { locale: string | null, country: string | null } };

export type UpdateProfileMutationVariables = Exact<{
  input: UpdateProfileInput;
}>;


export type UpdateProfileMutation = { updateProfile: { publicHandle: string, displayName: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string } | null } } };

export type DeleteAvatarMutationVariables = Exact<{ [key: string]: never; }>;


export type DeleteAvatarMutation = { deleteAvatar: { publicHandle: string, displayName: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string } | null } } };

export class TypedDocumentString<TResult, TVariables>
  extends String
  implements DocumentTypeDecoration<TResult, TVariables>
{
  __apiType?: NonNullable<DocumentTypeDecoration<TResult, TVariables>['__apiType']>;
  private value: string;
  public __meta__?: Record<string, any> | undefined;

  constructor(value: string, __meta__?: Record<string, any> | undefined) {
    super(value);
    this.value = value;
    this.__meta__ = __meta__;
  }

  override toString(): string & DocumentTypeDecoration<TResult, TVariables> {
    return this.value;
  }
}
export const PhotoFieldsFragmentDoc = new TypedDocumentString(`
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
    `, {"fragmentName":"PhotoFields"}) as unknown as TypedDocumentString<PhotoFieldsFragment, unknown>;
export const ProfileFieldsFragmentDoc = new TypedDocumentString(`
    fragment ProfileFields on Profile {
  firstName
  lastName
  phone
  contactEmail
  loginEmail
  visibility
  visibleFields {
    field
    audience
  }
  avatar {
    ...PhotoFields
  }
}
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}`, {"fragmentName":"ProfileFields"}) as unknown as TypedDocumentString<ProfileFieldsFragment, unknown>;
export const StoreFieldsFragmentDoc = new TypedDocumentString(`
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
    `, {"fragmentName":"StoreFields"}) as unknown as TypedDocumentString<StoreFieldsFragment, unknown>;
export const StoreDetailFieldsFragmentDoc = new TypedDocumentString(`
    fragment StoreDetailFields on Store {
  ...StoreFields
  photos {
    ...PhotoFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}`, {"fragmentName":"StoreDetailFields"}) as unknown as TypedDocumentString<StoreDetailFieldsFragment, unknown>;
export const ConvertedPriceFieldsFragmentDoc = new TypedDocumentString(`
    fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
    `, {"fragmentName":"ConvertedPriceFields"}) as unknown as TypedDocumentString<ConvertedPriceFieldsFragment, unknown>;
export const PriceCurrentFieldsFragmentDoc = new TypedDocumentString(`
    fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}`, {"fragmentName":"PriceCurrentFields"}) as unknown as TypedDocumentString<PriceCurrentFieldsFragment, unknown>;
export const ProductFieldsFragmentDoc = new TypedDocumentString(`
    fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}`, {"fragmentName":"ProductFields"}) as unknown as TypedDocumentString<ProductFieldsFragment, unknown>;
export const ProductDetailFieldsFragmentDoc = new TypedDocumentString(`
    fragment ProductDetailFields on Product {
  ...ProductFields
  gtin
  stats {
    observationCount
    storeCount
    lastObservedAt
    bestPrice
    bestUnitPrice
    bestPriceCurrency
    bestPriceConverted {
      ...ConvertedPriceFields
    }
    cheapestStore {
      ...StoreFields
    }
  }
  quality {
    average
    count
  }
  myQualityRating
  externalLinks {
    kind
    label
    url
    attribution
  }
  myPrices {
    store {
      ...StoreFields
    }
    priceKind
    priceAmount
    unitPrice
    currency
    observedAt
    converted {
      ...ConvertedPriceFields
    }
  }
  photos {
    ...PhotoFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}`, {"fragmentName":"ProductDetailFields"}) as unknown as TypedDocumentString<ProductDetailFieldsFragment, unknown>;
export const PublicationStatusFieldsFragmentDoc = new TypedDocumentString(`
    fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}
    `, {"fragmentName":"PublicationStatusFields"}) as unknown as TypedDocumentString<PublicationStatusFieldsFragment, unknown>;
export const ProductSummaryFieldsFragmentDoc = new TypedDocumentString(`
    fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}
    `, {"fragmentName":"ProductSummaryFields"}) as unknown as TypedDocumentString<ProductSummaryFieldsFragment, unknown>;
export const SearchItemFieldsFragmentDoc = new TypedDocumentString(`
    fragment SearchItemFields on ProductSearchItem {
  product {
    ...ProductSummaryFields
  }
  observationCount
  bestPrice
  bestUnitPrice
  currency
  bestPriceObservations
  lastObservedAt
  qualityAverage
  qualityCount
  converted {
    ...ConvertedPriceFields
  }
  convertedUnit {
    ...ConvertedPriceFields
  }
  cheapestStore {
    ...StoreFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}`, {"fragmentName":"SearchItemFields"}) as unknown as TypedDocumentString<SearchItemFieldsFragment, unknown>;
export const CountriesDocument = new TypedDocumentString(`
    query Countries {
  countries {
    code
    currency
    defaultLocale
  }
}
    `) as unknown as TypedDocumentString<CountriesQuery, CountriesQueryVariables>;
export const FxInfoDocument = new TypedDocumentString(`
    query FxInfo {
  fxInfo {
    displayCurrencies
    latestRateDate
    attribution
  }
}
    `) as unknown as TypedDocumentString<FxInfoQuery, FxInfoQueryVariables>;
export const UpdatePhotoDocument = new TypedDocumentString(`
    mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int) {
  updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder) {
    ...PhotoFields
  }
}
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}`) as unknown as TypedDocumentString<UpdatePhotoMutation, UpdatePhotoMutationVariables>;
export const DeletePhotoDocument = new TypedDocumentString(`
    mutation DeletePhoto($id: ID!) {
  deletePhoto(id: $id)
}
    `) as unknown as TypedDocumentString<DeletePhotoMutation, DeletePhotoMutationVariables>;
export const FlagPhotoDocument = new TypedDocumentString(`
    mutation FlagPhoto($recordId: ID!, $reason: String) {
  flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) {
    flagCount
    hidden
  }
}
    `) as unknown as TypedDocumentString<FlagPhotoMutation, FlagPhotoMutationVariables>;
export const MyProductsDocument = new TypedDocumentString(`
    query MyProducts($first: Int, $offset: Int) {
  myProducts(first: $first, offset: $offset) {
    totalCount
    items {
      createdAt
      publication {
        ...PublicationStatusFields
      }
      product {
        ...ProductSummaryFields
      }
    }
  }
}
    fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}
fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}`) as unknown as TypedDocumentString<MyProductsQuery, MyProductsQueryVariables>;
export const MyStoresDocument = new TypedDocumentString(`
    query MyStores($first: Int, $offset: Int) {
  myStores(first: $first, offset: $offset) {
    totalCount
    items {
      createdAt
      publication {
        ...PublicationStatusFields
      }
      store {
        ...StoreFields
      }
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}`) as unknown as TypedDocumentString<MyStoresQuery, MyStoresQueryVariables>;
export const MyObservationsDocument = new TypedDocumentString(`
    query MyObservations($first: Int, $offset: Int) {
  myObservations(first: $first, offset: $offset) {
    totalCount
    items {
      priceKind
      priceAmount
      unitPrice
      currency
      observedAt
      createdAt
      converted {
        ...ConvertedPriceFields
      }
      publication {
        ...PublicationStatusFields
      }
      product {
        ...ProductSummaryFields
      }
      store {
        ...StoreFields
      }
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}
fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}`) as unknown as TypedDocumentString<MyObservationsQuery, MyObservationsQueryVariables>;
export const MyEditsDocument = new TypedDocumentString(`
    query MyEdits($first: Int, $offset: Int) {
  myEdits(first: $first, offset: $offset) {
    totalCount
    items {
      recordType
      updatedAt
      changedFields
      publication {
        ...PublicationStatusFields
      }
      product {
        ...ProductSummaryFields
      }
      store {
        ...StoreFields
      }
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}
fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}`) as unknown as TypedDocumentString<MyEditsQuery, MyEditsQueryVariables>;
export const SearchProductsDocument = new TypedDocumentString(`
    query SearchProducts($query: String!, $storeId: ID, $city: String, $country: String, $sort: ProductSort, $first: Int, $offset: Int) {
  searchProducts(
    query: $query
    storeId: $storeId
    city: $city
    country: $country
    sort: $sort
    first: $first
    offset: $offset
  ) {
    totalCount
    hasMore
    items {
      ...SearchItemFields
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}
fragment SearchItemFields on ProductSearchItem {
  product {
    ...ProductSummaryFields
  }
  observationCount
  bestPrice
  bestUnitPrice
  currency
  bestPriceObservations
  lastObservedAt
  qualityAverage
  qualityCount
  converted {
    ...ConvertedPriceFields
  }
  convertedUnit {
    ...ConvertedPriceFields
  }
  cheapestStore {
    ...StoreFields
  }
}`) as unknown as TypedDocumentString<SearchProductsQuery, SearchProductsQueryVariables>;
export const SearchFacetsDocument = new TypedDocumentString(`
    query SearchFacets($country: String) {
  searchFacets(country: $country) {
    cities
    stores {
      ...StoreFields
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<SearchFacetsQuery, SearchFacetsQueryVariables>;
export const ProductDocument = new TypedDocumentString(`
    query Product($id: ID!) {
  product(id: $id) {
    ...ProductDetailFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}
fragment ProductDetailFields on Product {
  ...ProductFields
  gtin
  stats {
    observationCount
    storeCount
    lastObservedAt
    bestPrice
    bestUnitPrice
    bestPriceCurrency
    bestPriceConverted {
      ...ConvertedPriceFields
    }
    cheapestStore {
      ...StoreFields
    }
  }
  quality {
    average
    count
  }
  myQualityRating
  externalLinks {
    kind
    label
    url
    attribution
  }
  myPrices {
    store {
      ...StoreFields
    }
    priceKind
    priceAmount
    unitPrice
    currency
    observedAt
    converted {
      ...ConvertedPriceFields
    }
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<ProductQuery, ProductQueryVariables>;
export const ProductByCodeDocument = new TypedDocumentString(`
    query ProductByCode($code: String!) {
  productByCode(code: $code) {
    ...ProductDetailFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}
fragment ProductDetailFields on Product {
  ...ProductFields
  gtin
  stats {
    observationCount
    storeCount
    lastObservedAt
    bestPrice
    bestUnitPrice
    bestPriceCurrency
    bestPriceConverted {
      ...ConvertedPriceFields
    }
    cheapestStore {
      ...StoreFields
    }
  }
  quality {
    average
    count
  }
  myQualityRating
  externalLinks {
    kind
    label
    url
    attribution
  }
  myPrices {
    store {
      ...StoreFields
    }
    priceKind
    priceAmount
    unitPrice
    currency
    observedAt
    converted {
      ...ConvertedPriceFields
    }
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<ProductByCodeQuery, ProductByCodeQueryVariables>;
export const ProductSuggestionsDocument = new TypedDocumentString(`
    query ProductSuggestions($name: String!, $first: Int) {
  productSuggestions(name: $name, first: $first) {
    ...ProductSummaryFields
  }
}
    fragment ProductSummaryFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  isGeneric
  verified
  editedByMe
}`) as unknown as TypedDocumentString<ProductSuggestionsQuery, ProductSuggestionsQueryVariables>;
export const CategoriesDocument = new TypedDocumentString(`
    query Categories {
  categories {
    id
    name
    slug
    path
  }
}
    `) as unknown as TypedDocumentString<CategoriesQuery, CategoriesQueryVariables>;
export const CreateProductDocument = new TypedDocumentString(`
    mutation CreateProduct($input: CreateProductInput!) {
  createProduct(input: $input) {
    ...ProductDetailFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}
fragment ProductDetailFields on Product {
  ...ProductFields
  gtin
  stats {
    observationCount
    storeCount
    lastObservedAt
    bestPrice
    bestUnitPrice
    bestPriceCurrency
    bestPriceConverted {
      ...ConvertedPriceFields
    }
    cheapestStore {
      ...StoreFields
    }
  }
  quality {
    average
    count
  }
  myQualityRating
  externalLinks {
    kind
    label
    url
    attribution
  }
  myPrices {
    store {
      ...StoreFields
    }
    priceKind
    priceAmount
    unitPrice
    currency
    observedAt
    converted {
      ...ConvertedPriceFields
    }
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<CreateProductMutation, CreateProductMutationVariables>;
export const UpdateProductDocument = new TypedDocumentString(`
    mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {
  updateProduct(id: $id, input: $input) {
    ...ProductDetailFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ConvertedPriceFields on ConvertedPrice {
  amount
  currency
  rateDate
}
fragment PriceCurrentFields on PriceCurrent {
  store {
    ...StoreFields
  }
  priceKind
  unitPrice
  priceAmount
  currency
  nObs
  nEff
  lastObservedAt
  confidence
  converted {
    ...ConvertedPriceFields
  }
}
fragment ProductFields on Product {
  id
  name
  brand {
    id
    name
    slug
  }
  category {
    id
    name
    slug
    path
  }
  unitBase
  netContentValue
  netContentUom
  netContentBase
  piecesInPack
  isVariableWeight
  status
  isGeneric
  verified
  editedByMe
  prices {
    ...PriceCurrentFields
  }
}
fragment ProductDetailFields on Product {
  ...ProductFields
  gtin
  stats {
    observationCount
    storeCount
    lastObservedAt
    bestPrice
    bestUnitPrice
    bestPriceCurrency
    bestPriceConverted {
      ...ConvertedPriceFields
    }
    cheapestStore {
      ...StoreFields
    }
  }
  quality {
    average
    count
  }
  myQualityRating
  externalLinks {
    kind
    label
    url
    attribution
  }
  myPrices {
    store {
      ...StoreFields
    }
    priceKind
    priceAmount
    unitPrice
    currency
    observedAt
    converted {
      ...ConvertedPriceFields
    }
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<UpdateProductMutation, UpdateProductMutationVariables>;
export const FlagProductDocument = new TypedDocumentString(`
    mutation FlagProduct($recordId: ID!, $reason: String) {
  flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) {
    flagCount
    hidden
  }
}
    `) as unknown as TypedDocumentString<FlagProductMutation, FlagProductMutationVariables>;
export const PriceHistoryDocument = new TypedDocumentString(`
    query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {
  priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {
    priceKind
    days
    currency
    displayCurrency
    rateAttribution
    store {
      ...StoreFields
    }
    points {
      day
      priceAmount
      unitPrice
      nObs
      storeCount
      convertedUnitPrice
      convertedPriceAmount
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<PriceHistoryQuery, PriceHistoryQueryVariables>;
export const RateProductDocument = new TypedDocumentString(`
    mutation RateProduct($productId: ID!, $grade: Int!) {
  rateProduct(productId: $productId, grade: $grade) {
    average
    count
  }
}
    `) as unknown as TypedDocumentString<RateProductMutation, RateProductMutationVariables>;
export const SubmitObservationDocument = new TypedDocumentString(`
    mutation SubmitObservation($input: SubmitObservationInput!) {
  submitObservation(input: $input) {
    id
    priceAmount
    currency
    unitPrice
    priceKind
    quantityBasis
    observedAt
    status
  }
}
    `) as unknown as TypedDocumentString<SubmitObservationMutation, SubmitObservationMutationVariables>;
export const NearbyStoresDocument = new TypedDocumentString(`
    query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {
  nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {
    ...StoreFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<NearbyStoresQuery, NearbyStoresQueryVariables>;
export const SearchStoresDocument = new TypedDocumentString(`
    query SearchStores($query: String, $city: String, $first: Int) {
  searchStores(query: $query, city: $city, first: $first) {
    totalCount
    hasMore
    items {
      ...StoreFields
    }
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<SearchStoresQuery, SearchStoresQueryVariables>;
export const StoreDocument = new TypedDocumentString(`
    query Store($id: ID!) {
  store(id: $id) {
    ...StoreDetailFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}
fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment StoreDetailFields on Store {
  ...StoreFields
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<StoreQuery, StoreQueryVariables>;
export const CreateStoreDocument = new TypedDocumentString(`
    mutation CreateStore($input: CreateStoreInput!) {
  createStore(input: $input) {
    ...StoreFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<CreateStoreMutation, CreateStoreMutationVariables>;
export const UpdateStoreDocument = new TypedDocumentString(`
    mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {
  updateStore(id: $id, input: $input) {
    ...StoreFields
  }
}
    fragment StoreFields on Store {
  id
  name
  street
  city
  postalCode
  country
  lat
  lon
  geoSource
  ico
  chain {
    id
    name
    chainType
  }
  verified
  editedByMe
  pendingConfirmation
}`) as unknown as TypedDocumentString<UpdateStoreMutation, UpdateStoreMutationVariables>;
export const FlagStoreDocument = new TypedDocumentString(`
    mutation FlagStore($recordId: ID!, $reason: String) {
  flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) {
    flagCount
    hidden
  }
}
    `) as unknown as TypedDocumentString<FlagStoreMutation, FlagStoreMutationVariables>;
export const GeocodeAddressDocument = new TypedDocumentString(`
    query GeocodeAddress($street: String, $city: String!, $postalCode: String) {
  geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {
    attribution
    candidates {
      lat
      lon
      displayName
      osmRef
    }
  }
}
    `) as unknown as TypedDocumentString<GeocodeAddressQuery, GeocodeAddressQueryVariables>;
export const ReverseGeocodeDocument = new TypedDocumentString(`
    query ReverseGeocode($lat: Float!, $lon: Float!) {
  reverseGeocode(lat: $lat, lon: $lon) {
    street
    city
    postalCode
    country
    osmRef
    attribution
  }
}
    `) as unknown as TypedDocumentString<ReverseGeocodeQuery, ReverseGeocodeQueryVariables>;
export const CompanyByIcoDocument = new TypedDocumentString(`
    query CompanyByIco($ico: String!) {
  companyByIco(ico: $ico) {
    ico
    name
    street
    city
    postalCode
  }
}
    `) as unknown as TypedDocumentString<CompanyByIcoQuery, CompanyByIcoQueryVariables>;
export const MeDocument = new TypedDocumentString(`
    query Me {
  me {
    publicHandle
    displayName
    createdAt
    trusted
    locale
    country
    profile {
      ...ProfileFields
    }
  }
}
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ProfileFields on Profile {
  firstName
  lastName
  phone
  contactEmail
  loginEmail
  visibility
  visibleFields {
    field
    audience
  }
  avatar {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<MeQuery, MeQueryVariables>;
export const SetLocaleDocument = new TypedDocumentString(`
    mutation SetLocale($locale: String!, $country: String) {
  setLocale(locale: $locale, country: $country) {
    locale
    country
  }
}
    `) as unknown as TypedDocumentString<SetLocaleMutation, SetLocaleMutationVariables>;
export const UpdateProfileDocument = new TypedDocumentString(`
    mutation UpdateProfile($input: UpdateProfileInput!) {
  updateProfile(input: $input) {
    publicHandle
    displayName
    profile {
      ...ProfileFields
    }
  }
}
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ProfileFields on Profile {
  firstName
  lastName
  phone
  contactEmail
  loginEmail
  visibility
  visibleFields {
    field
    audience
  }
  avatar {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<UpdateProfileMutation, UpdateProfileMutationVariables>;
export const DeleteAvatarDocument = new TypedDocumentString(`
    mutation DeleteAvatar {
  deleteAvatar {
    publicHandle
    displayName
    profile {
      ...ProfileFields
    }
  }
}
    fragment PhotoFields on Photo {
  id
  url
  thumbnailUrl
  width
  height
  caption
  mine
  hidden
  attribution
}
fragment ProfileFields on Profile {
  firstName
  lastName
  phone
  contactEmail
  loginEmail
  visibility
  visibleFields {
    field
    audience
  }
  avatar {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<DeleteAvatarMutation, DeleteAvatarMutationVariables>;