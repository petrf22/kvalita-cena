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

export type CatalogDataSource =
  | 'COMMUNITY'
  | 'OPEN_FOOD_FACTS';

export type ChainType =
  | 'CHAIN'
  | 'FARM_SHOP'
  | 'INDEPENDENT'
  | 'MARKET'
  | 'ONLINE';

/** Odkud přišel request (core.feedback.client_kind) — odvozeno server-side z X-Client-Kind, nikdy z inputu. */
export type ClientKind =
  | 'ANDROID'
  | 'WEB';

export type Confidence =
  | 'HIGH'
  | 'LOW'
  | 'MEDIUM';

export type CreateProductFromOffInput = {
  brandName?: string | null | undefined;
  categoryId?: string | null | undefined;
  code: string;
  isVariableWeight?: boolean | null | undefined;
  name?: string | null | undefined;
  netContentUom?: NetContentUom | null | undefined;
  netContentValue?: number | null | undefined;
  piecesInPack?: number | null | undefined;
  unitBase?: UnitBase | null | undefined;
};

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
  /** Odkaz na stránku provozovny (u řetězce) — jako street/city jde přes core.store_user_edit. */
  url?: string | null | undefined;
};

export type ExternalLinkKind =
  | 'E_NUMBERS'
  | 'OPEN_FOOD_FACTS';

/** Kategorie zpětné vazby (core.feedback) — jen k roztřídění fronty, nemění chování. */
export type FeedbackCategory =
  | 'BUG'
  | 'CONTENT'
  | 'IDEA'
  | 'OTHER';

export type FeedbackInput = {
  appVersion?: string | null | undefined;
  category: FeedbackCategory;
  /** Token z feedbackChallenge, beze změny. Nepovinné jen kvůli starším klientům bez PoW (docs/nasazeni.md). */
  challenge?: string | null | undefined;
  /** Nepovinný — odpověď je pak možná jen u přihlášeného odesílatele. */
  contactEmail?: string | null | undefined;
  /** Volitelně přiložený záznam o posledním pádu appky (mobil) — nikdy neodchází bez akce uživatele. */
  diagnostics?: string | null | undefined;
  message: string;
  /** Vyřešený nonce k challenge — SHA-256(salt + ":" + nonce) musí mít difficulty vedoucích nulových bitů. */
  nonce?: string | null | undefined;
  /** Route/obrazovka, ze které se odesílalo (jen orientační). */
  pageRef?: string | null | undefined;
  /**
   * Honeypot proti spamovacím botům — appka pole nikdy nevyplní, jen ho v UI schová (CSS, ne
   * hidden atribut, který by bot mohl přeskočit). Nepoužívá se na mobilu, appka ho tam neposílá.
   */
  website?: string | null | undefined;
};

/** Výsledek moderátorského přezkumu nahlášeného záznamu (docs/reputace.md, 'Moderace'). */
export type FlagResolution =
  /** Nahlášení bylo neopodstatněné — hidden_at cíle se vrátí na NULL. */
  | 'DISMISSED'
  /** Nahlášení bylo oprávněné — cíl zůstává (nebo se nově nastaví) skrytý. */
  | 'UPHELD';

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

/** Jeden řádek formuláře: druh ceny + částka. MULTIBUY má místo priceAmount dvojici qty/total. */
export type ObservationPriceInput = {
  /** Povinné pro MULTIBUY (např. „3 za 50“). */
  multibuyQty?: number | null | undefined;
  multibuyTotal?: number | null | undefined;
  /** Povinná pro všechny druhy kromě MULTIBUY, kde se cena odvodí z multibuyTotal. */
  priceAmount?: number | null | undefined;
  priceKind?: PriceKind;
  /**
   * Platnost akce — smí se vyplnit jen u PROMO (jinak OBSERVATION_PROMO_VALIDITY_NOT_ALLOWED).
   * Obě pole nepovinná. Nesmí být v budoucnu — zapisuje se cena, kterou uživatel VIDĚL v regále,
   * ne cena z letáku, která ještě nezačala platit (jinak OBSERVATION_PROMO_VALID_FROM_IN_FUTURE).
   */
  promoValidFrom?: string | null | undefined;
  /** Platnost akce — do kdy platí. Musí být >= promoValidFrom, jinak OBSERVATION_PROMO_VALIDITY_RANGE_INVALID. */
  promoValidTo?: string | null | undefined;
};

export type ObservationStatus =
  | 'ACTIVE'
  | 'DISPUTED'
  | 'PENDING'
  | 'REJECTED';

/**
 * Druh fotky (core.media.photo_kind) — nezávislá osa od RecordType (ten říká čí je fotka, tohle
 * co na ní je). ITEM, ne PRODUCT — RecordType.PRODUCT už znamená totéž na jiné ose. Posílá se při
 * uploadu jako parametr kind REST endpointu POST /api/media/{recordType}/{recordId}.
 */
export type PhotoKind =
  /** Fotka samotného zboží/obalu. */
  | 'ITEM'
  /** Fotka etikety — zamýšlený budoucí vstup pro čtení složení/textu z etikety (docs/ai.md). */
  | 'LABEL'
  /** Cokoli jiného, včetně fotek provozoven a avatarů (druh nerozlišují, zůstávají na OTHER). */
  | 'OTHER';

export type PriceKind =
  | 'CLEARANCE'
  | 'CLUB_CARD'
  | 'MULTIBUY'
  | 'PROMO'
  | 'REGULAR';

export type ProductLookupStatus =
  | 'EXISTING'
  | 'NOT_FOUND'
  | 'OFF_CANDIDATE'
  | 'OFF_UNAVAILABLE';

export type ProductSort =
  | 'LAST_REPORTED'
  | 'NAME'
  | 'PRICE_ASC'
  /** 5 = nejlepší, tedy sestupně. */
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
  /** Nahlašovaný typ pro TEXT recenze (core.product_review.text), ne pro hodnocení samotné ani autora. */
  | 'REVIEW'
  | 'STORE';

/**
 * Hlavička dávky: co, kde a kdy. Tyhle údaje jsou z podstaty společné všem cenám z jednoho
 * regálu — kdyby byly per řádek, šlo by poslat REGULAR v CZK a CLUB_CARD v EUR pro tutéž
 * cenovku (docs/lokalizace.md, "Multi-měna") nebo dva různé produkty v jedné "dávce", u které
 * by pak nedávalo smysl, co se má přepočítat (jedna položka agg.recompute_queue).
 */
export type SubmitObservationsInput = {
  /**
   * ISO-4217 kód měny — chybí-li, dosadí se podle země obchodu (docs/lokalizace.md). Volitelný
   * jen kvůli příhraničním prodejnám, které občas cení v jiné měně, než je země provozovny;
   * server hodnotu validuje proti podporovaným měnám, neplatnou tiše ignoruje a dosadí zemi obchodu.
   */
  currency?: string | null | undefined;
  /** Kdy uživatel cenu viděl — chybí-li, použije se teď (docs/datovy-model.md, observed_at ≠ created_at). */
  observedAt?: string | null | undefined;
  /**
   * 1–5 cen z jednoho regálu. Druh ceny se v seznamu NESMÍ opakovat (nejvýš jedna cena každého
   * druhu na uživatele/produkt/obchod/den) — duplicita shodí celou dávku
   * s OBSERVATION_DUPLICATE_PRICE_KIND.
   */
  prices: Array<ObservationPriceInput>;
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
  clearUrl?: boolean | null | undefined;
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
  /** Odkaz na stránku provozovny (u řetězce) — jako street/city jde přes core.store_user_edit. */
  url?: string | null | undefined;
};

export type CountriesQueryVariables = Exact<{ [key: string]: never; }>;


export type CountriesQuery = { countries: Array<{ code: string, currency: string, defaultLocale: string | null }> };

export type SubmitFeedbackMutationVariables = Exact<{
  input: FeedbackInput;
}>;


export type SubmitFeedbackMutation = { submitFeedback: { id: string } };

export type FeedbackChallengeQueryVariables = Exact<{ [key: string]: never; }>;


export type FeedbackChallengeQuery = { feedbackChallenge: { token: string, salt: string, difficulty: number } };

export type FxInfoQueryVariables = Exact<{ [key: string]: never; }>;


export type FxInfoQuery = { fxInfo: { displayCurrencies: Array<string>, latestRateDate: string | null, attribution: string } };

export type StoreFieldsFragment = { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null };

export type PhotoFieldsFragment = { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind };

export type ProfileFieldsFragment = { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } | null };

export type StoreDetailFieldsFragment = { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, chain: { id: string, name: string, chainType: ChainType } | null };

export type ConvertedPriceFieldsFragment = { amount: number, currency: string, rateDate: string };

export type PriceCurrentFieldsFragment = { priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null };

export type ProductFieldsFragment = { id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> };

export type ProductDetailFieldsFragment = { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> };

export type ProductSummaryFieldsFragment = { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null };

export type PublicationStatusFieldsFragment = { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean };

export type ProductReviewFieldsFragment = { id: string, stars: number, text: string, authorPublicUid: string, authorName: string, createdAt: string, updatedAt: string | null, mine: boolean };

export type SearchItemFieldsFragment = { observationCount: number, bestPrice: number | null, bestUnitPrice: number | null, currency: string | null, bestPriceObservations: number | null, lastObservedAt: string | null, qualityAverage: number | null, qualityCount: number, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null }, converted: { amount: number, currency: string, rateDate: string } | null, convertedUnit: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null };

export type UpdatePhotoMutationVariables = Exact<{
  id: string;
  caption?: string | null | undefined;
  sortOrder?: number | null | undefined;
  kind?: PhotoKind | null | undefined;
}>;


export type UpdatePhotoMutation = { updatePhoto: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } };

export type DeletePhotoMutationVariables = Exact<{
  id: string;
}>;


export type DeletePhotoMutation = { deletePhoto: boolean };

export type FlagPhotoMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagPhotoMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type FlaggedRecordsQueryVariables = Exact<{
  recordType?: RecordType | null | undefined;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type FlaggedRecordsQuery = { flaggedRecords: { totalCount: number, items: Array<{ recordType: RecordType, recordId: string, flagCount: number, firstFlaggedAt: string, lastFlaggedAt: string, reasons: Array<string>, hidden: boolean, authorPublicUid: string | null, authorHandle: string | null, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null } | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null, photo: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } | null }> } };

export type ResolveFlagsMutationVariables = Exact<{
  recordType: RecordType;
  recordId: string;
  resolution: FlagResolution;
}>;


export type ResolveFlagsMutation = { resolveFlags: boolean };

export type ModerationObservationsQueryVariables = Exact<{
  productId?: string | null | undefined;
  storeId?: string | null | undefined;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type ModerationObservationsQuery = { moderationObservations: { totalCount: number, items: Array<{ authorPublicUid: string | null, authorHandle: string | null, observation: { id: string, priceAmount: number, currency: string, priceKind: PriceKind, unitPrice: number | null, observedAt: string, status: ObservationStatus, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null }, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } } }> } };

export type SetObservationRejectedMutationVariables = Exact<{
  id: string;
  rejected: boolean;
  reason?: string | null | undefined;
}>;


export type SetObservationRejectedMutation = { setObservationRejected: { id: string, status: ObservationStatus } };

export type SetUserSuspendedMutationVariables = Exact<{
  publicUid: string;
  suspended: boolean;
  reason?: string | null | undefined;
}>;


export type SetUserSuspendedMutation = { setUserSuspended: boolean };

export type FeedbackItemsQueryVariables = Exact<{
  handled?: boolean | null | undefined;
  quarantined?: boolean | null | undefined;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type FeedbackItemsQuery = { feedbackItems: { totalCount: number, items: Array<{ id: string, category: FeedbackCategory, message: string, contactEmail: string | null, clientKind: ClientKind, appVersion: string | null, platformInfo: string | null, locale: string | null, country: string | null, pageRef: string | null, diagnostics: string | null, createdAt: string, handled: boolean, handledNote: string | null, authorPublicUid: string | null, authorHandle: string | null, spamScore: number, spamReasons: Array<string>, quarantined: boolean }> } };

export type SetFeedbackHandledMutationVariables = Exact<{
  id: string;
  handled: boolean;
  note?: string | null | undefined;
}>;


export type SetFeedbackHandledMutation = { setFeedbackHandled: boolean };

export type SetFeedbackQuarantinedMutationVariables = Exact<{
  id: string;
  quarantined: boolean;
}>;


export type SetFeedbackQuarantinedMutation = { setFeedbackQuarantined: boolean };

export type MyProductsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyProductsQuery = { myProducts: { totalCount: number, items: Array<{ createdAt: string, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null } }> } };

export type MyStoresQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyStoresQuery = { myStores: { totalCount: number, items: Array<{ createdAt: string, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } }> } };

export type MyObservationsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyObservationsQuery = { myObservations: { totalCount: number, items: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, promoValidFrom: string | null, promoValidTo: string | null, observedAt: string, createdAt: string, converted: { amount: number, currency: string, rateDate: string } | null, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null }, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } }> } };

export type MyEditsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyEditsQuery = { myEdits: { totalCount: number, items: Array<{ recordType: RecordType, updatedAt: string, changedFields: Array<string>, publication: { state: PublicationState, confirmationsReceived: number | null, confirmationsRequired: number | null, verified: boolean }, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null } | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }> } };

export type MyReviewsQueryVariables = Exact<{
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type MyReviewsQuery = { myReviews: { totalCount: number, items: Array<{ stars: number, text: string, createdAt: string, updatedAt: string | null, hidden: boolean, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null } }> } };

export type SearchProductsQueryVariables = Exact<{
  query: string;
  storeId?: string | null | undefined;
  city?: string | null | undefined;
  categoryId?: string | null | undefined;
  country?: string | null | undefined;
  sort?: ProductSort | null | undefined;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type SearchProductsQuery = { searchProducts: { totalCount: number, hasMore: boolean, items: Array<{ observationCount: number, bestPrice: number | null, bestUnitPrice: number | null, currency: string | null, bestPriceObservations: number | null, lastObservedAt: string | null, qualityAverage: number | null, qualityCount: number, product: { id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null }, converted: { amount: number, currency: string, rateDate: string } | null, convertedUnit: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }> } };

export type SearchFacetsQueryVariables = Exact<{
  country?: string | null | undefined;
}>;


export type SearchFacetsQuery = { searchFacets: { cities: Array<string>, stores: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> } };

export type ProductQueryVariables = Exact<{
  id: string;
}>;


export type ProductQuery = { product: { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } | null };

export type ProductLookupByCodeQueryVariables = Exact<{
  code: string;
}>;


export type ProductLookupByCodeQuery = { productLookupByCode: { status: ProductLookupStatus, product: { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } | null, candidate: { code: string, name: string | null, brandName: string | null, unitBase: UnitBase | null, netContentValue: number | null, netContentUom: NetContentUom | null, sourceUrl: string, attribution: string, category: { id: string, name: string, slug: string, path: string } | null, image: { url: string, thumbnailUrl: string, attribution: string } | null } | null } };

export type ProductSuggestionsQueryVariables = Exact<{
  name: string;
  first?: number | null | undefined;
}>;


export type ProductSuggestionsQuery = { productSuggestions: Array<{ id: string, name: string, isGeneric: boolean, verified: boolean, editedByMe: boolean, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, photos: Array<{ id: string, thumbnailUrl: string }>, externalImage: { thumbnailUrl: string, attribution: string } | null }> };

export type CategoriesQueryVariables = Exact<{ [key: string]: never; }>;


export type CategoriesQuery = { categories: Array<{ id: string, name: string, slug: string, path: string, sortOrder: number }> };

export type CreateProductMutationVariables = Exact<{
  input: CreateProductInput;
}>;


export type CreateProductMutation = { createProduct: { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } };

export type CreateProductFromOffMutationVariables = Exact<{
  input: CreateProductFromOffInput;
}>;


export type CreateProductFromOffMutation = { createProductFromOff: { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } };

export type UpdateProductMutationVariables = Exact<{
  id: string;
  input: UpdateProductInput;
}>;


export type UpdateProductMutation = { updateProduct: { gtin: string | null, myQualityRating: number | null, reviewCount: number, myReviewText: string | null, id: string, name: string, catalogSource: CatalogDataSource, catalogAttribution: string | null, unitBase: UnitBase, netContentValue: number | null, netContentUom: NetContentUom | null, netContentBase: number, piecesInPack: number | null, isVariableWeight: boolean, status: ProductStatus, isGeneric: boolean, verified: boolean, editedByMe: boolean, stats: { observationCount: number, storeCount: number, lastObservedAt: string | null, bestPrice: number | null, bestUnitPrice: number | null, bestPriceCurrency: string | null, bestPriceConverted: { amount: number, currency: string, rateDate: string } | null, cheapestStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null }, quality: { average: number | null, count: number }, externalLinks: Array<{ kind: ExternalLinkKind, label: string, url: string, attribution: string }>, myPrices: Array<{ priceKind: PriceKind, priceAmount: number, unitPrice: number | null, currency: string, observedAt: string, promoValidFrom: string | null, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }>, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, externalImage: { url: string, thumbnailUrl: string, attribution: string } | null, brand: { id: string, name: string, slug: string } | null, category: { id: string, name: string, slug: string, path: string }, prices: Array<{ priceKind: PriceKind, unitPrice: number | null, priceAmount: number | null, currency: string, nObs: number, nEff: number, lastObservedAt: string | null, confidence: Confidence, promoValidTo: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }, converted: { amount: number, currency: string, rateDate: string } | null }> } };

export type FlagProductMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagProductMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type FlagReviewMutationVariables = Exact<{
  recordId: string;
  reason?: string | null | undefined;
}>;


export type FlagReviewMutation = { flagRecord: { flagCount: number, hidden: boolean } };

export type PriceHistoryQueryVariables = Exact<{
  productId: string;
  priceKind?: PriceKind | null | undefined;
  days?: number | null | undefined;
}>;


export type PriceHistoryQuery = { priceHistory: { priceKind: PriceKind, days: number, currency: string, displayCurrency: string | null, rateAttribution: string | null, store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } | null, points: Array<{ day: string, priceAmount: number | null, unitPrice: number, nObs: number, storeCount: number, convertedUnitPrice: number | null, convertedPriceAmount: number | null }> } };

export type RateProductMutationVariables = Exact<{
  productId: string;
  stars: number;
}>;


export type RateProductMutation = { rateProduct: { average: number | null, count: number } };

export type ProductReviewsQueryVariables = Exact<{
  productId: string;
  first?: number | null | undefined;
  offset?: number | null | undefined;
}>;


export type ProductReviewsQuery = { productReviews: { totalCount: number, hasMore: boolean, loginRequired: boolean, items: Array<{ id: string, stars: number, text: string, authorPublicUid: string, authorName: string, createdAt: string, updatedAt: string | null, mine: boolean }> } };

export type SaveProductReviewTextMutationVariables = Exact<{
  productId: string;
  text: string;
}>;


export type SaveProductReviewTextMutation = { saveProductReviewText: { stars: number, text: string | null, updatedAt: string | null } };

export type DeleteProductReviewTextMutationVariables = Exact<{
  productId: string;
}>;


export type DeleteProductReviewTextMutation = { deleteProductReviewText: { stars: number, text: string | null, updatedAt: string | null } };

export type SubmitObservationsMutationVariables = Exact<{
  input: SubmitObservationsInput;
}>;


export type SubmitObservationsMutation = { submitObservations: Array<{ id: string, priceAmount: number, currency: string, unitPrice: number | null, priceKind: PriceKind, quantityBasis: QuantityBasis, promoValidFrom: string | null, promoValidTo: string | null, observedAt: string, status: ObservationStatus }> };

export type NearbyStoresQueryVariables = Exact<{
  lat: number;
  lon: number;
  radiusKm?: number | null | undefined;
}>;


export type NearbyStoresQuery = { nearbyStores: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> };

export type SearchStoresQueryVariables = Exact<{
  query?: string | null | undefined;
  city?: string | null | undefined;
  first?: number | null | undefined;
}>;


export type SearchStoresQuery = { searchStores: { totalCount: number, hasMore: boolean, items: Array<{ id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null }> } };

export type StoreQueryVariables = Exact<{
  id: string;
}>;


export type StoreQuery = { store: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, photos: Array<{ id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind }>, chain: { id: string, name: string, chainType: ChainType } | null } | null };

export type CreateStoreMutationVariables = Exact<{
  input: CreateStoreInput;
}>;


export type CreateStoreMutation = { createStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } };

export type UpdateStoreMutationVariables = Exact<{
  id: string;
  input: UpdateStoreInput;
}>;


export type UpdateStoreMutation = { updateStore: { id: string, name: string, street: string | null, city: string, postalCode: string | null, country: string, lat: number | null, lon: number | null, geoSource: GeoSource, ico: string | null, url: string | null, verified: boolean, editedByMe: boolean, pendingConfirmation: boolean, chain: { id: string, name: string, chainType: ChainType } | null } };

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

export type ChainsQueryVariables = Exact<{
  query?: string | null | undefined;
  country?: string | null | undefined;
  first?: number | null | undefined;
}>;


export type ChainsQuery = { chains: Array<{ id: string, name: string, chainType: ChainType }> };

export type CompanyByIcoQueryVariables = Exact<{
  ico: string;
}>;


export type CompanyByIcoQuery = { companyByIco: { ico: string, name: string, street: string | null, city: string | null, postalCode: string | null } | null };

export type MeQueryVariables = Exact<{ [key: string]: never; }>;


export type MeQuery = { me: { publicHandle: string, displayName: string | null, createdAt: string, trusted: boolean, moderator: boolean, locale: string | null, country: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } | null } } | null };

export type SetLocaleMutationVariables = Exact<{
  locale: string;
  country?: string | null | undefined;
}>;


export type SetLocaleMutation = { setLocale: { locale: string | null, country: string | null } };

export type UpdateProfileMutationVariables = Exact<{
  input: UpdateProfileInput;
}>;


export type UpdateProfileMutation = { updateProfile: { publicHandle: string, displayName: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } | null } } };

export type DeleteAvatarMutationVariables = Exact<{ [key: string]: never; }>;


export type DeleteAvatarMutation = { deleteAvatar: { publicHandle: string, displayName: string | null, profile: { firstName: string | null, lastName: string | null, phone: string | null, contactEmail: string | null, loginEmail: string, visibility: ProfileVisibility, visibleFields: Array<{ field: ProfileField, audience: Audience }>, avatar: { id: string, url: string, thumbnailUrl: string, width: number, height: number, caption: string | null, mine: boolean, hidden: boolean, attribution: string, kind: PhotoKind } | null } } };

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
  kind
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
  kind
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
  url
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
  url
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
  kind
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
  promoValidTo
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
  url
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
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  url
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
  promoValidTo
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
export const ProductReviewFieldsFragmentDoc = new TypedDocumentString(`
    fragment ProductReviewFields on ProductReview {
  id
  stars
  text
  authorPublicUid
  authorName
  createdAt
  updatedAt
  mine
}
    `, {"fragmentName":"ProductReviewFields"}) as unknown as TypedDocumentString<ProductReviewFieldsFragment, unknown>;
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
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
  url
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
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
export const SubmitFeedbackDocument = new TypedDocumentString(`
    mutation SubmitFeedback($input: FeedbackInput!) {
  submitFeedback(input: $input) {
    id
  }
}
    `) as unknown as TypedDocumentString<SubmitFeedbackMutation, SubmitFeedbackMutationVariables>;
export const FeedbackChallengeDocument = new TypedDocumentString(`
    query FeedbackChallenge {
  feedbackChallenge {
    token
    salt
    difficulty
  }
}
    `) as unknown as TypedDocumentString<FeedbackChallengeQuery, FeedbackChallengeQueryVariables>;
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
    mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int, $kind: PhotoKind) {
  updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder, kind: $kind) {
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
  kind
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
export const FlaggedRecordsDocument = new TypedDocumentString(`
    query FlaggedRecords($recordType: RecordType, $first: Int, $offset: Int) {
  flaggedRecords(recordType: $recordType, first: $first, offset: $offset) {
    totalCount
    items {
      recordType
      recordId
      flagCount
      firstFlaggedAt
      lastFlaggedAt
      reasons
      hidden
      authorPublicUid
      authorHandle
      product {
        ...ProductSummaryFields
      }
      store {
        ...StoreFields
      }
      photo {
        ...PhotoFields
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
  url
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
  kind
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
}`) as unknown as TypedDocumentString<FlaggedRecordsQuery, FlaggedRecordsQueryVariables>;
export const ResolveFlagsDocument = new TypedDocumentString(`
    mutation ResolveFlags($recordType: RecordType!, $recordId: ID!, $resolution: FlagResolution!) {
  resolveFlags(
    recordType: $recordType
    recordId: $recordId
    resolution: $resolution
  )
}
    `) as unknown as TypedDocumentString<ResolveFlagsMutation, ResolveFlagsMutationVariables>;
export const ModerationObservationsDocument = new TypedDocumentString(`
    query ModerationObservations($productId: ID, $storeId: ID, $first: Int, $offset: Int) {
  moderationObservations(
    productId: $productId
    storeId: $storeId
    first: $first
    offset: $offset
  ) {
    totalCount
    items {
      authorPublicUid
      authorHandle
      observation {
        id
        priceAmount
        currency
        priceKind
        unitPrice
        observedAt
        status
        product {
          ...ProductSummaryFields
        }
        store {
          ...StoreFields
        }
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
  url
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
}`) as unknown as TypedDocumentString<ModerationObservationsQuery, ModerationObservationsQueryVariables>;
export const SetObservationRejectedDocument = new TypedDocumentString(`
    mutation SetObservationRejected($id: ID!, $rejected: Boolean!, $reason: String) {
  setObservationRejected(id: $id, rejected: $rejected, reason: $reason) {
    id
    status
  }
}
    `) as unknown as TypedDocumentString<SetObservationRejectedMutation, SetObservationRejectedMutationVariables>;
export const SetUserSuspendedDocument = new TypedDocumentString(`
    mutation SetUserSuspended($publicUid: ID!, $suspended: Boolean!, $reason: String) {
  setUserSuspended(publicUid: $publicUid, suspended: $suspended, reason: $reason)
}
    `) as unknown as TypedDocumentString<SetUserSuspendedMutation, SetUserSuspendedMutationVariables>;
export const FeedbackItemsDocument = new TypedDocumentString(`
    query FeedbackItems($handled: Boolean, $quarantined: Boolean, $first: Int, $offset: Int) {
  feedbackItems(
    handled: $handled
    quarantined: $quarantined
    first: $first
    offset: $offset
  ) {
    totalCount
    items {
      id
      category
      message
      contactEmail
      clientKind
      appVersion
      platformInfo
      locale
      country
      pageRef
      diagnostics
      createdAt
      handled
      handledNote
      authorPublicUid
      authorHandle
      spamScore
      spamReasons
      quarantined
    }
  }
}
    `) as unknown as TypedDocumentString<FeedbackItemsQuery, FeedbackItemsQueryVariables>;
export const SetFeedbackHandledDocument = new TypedDocumentString(`
    mutation SetFeedbackHandled($id: ID!, $handled: Boolean!, $note: String) {
  setFeedbackHandled(id: $id, handled: $handled, note: $note)
}
    `) as unknown as TypedDocumentString<SetFeedbackHandledMutation, SetFeedbackHandledMutationVariables>;
export const SetFeedbackQuarantinedDocument = new TypedDocumentString(`
    mutation SetFeedbackQuarantined($id: ID!, $quarantined: Boolean!) {
  setFeedbackQuarantined(id: $id, quarantined: $quarantined)
}
    `) as unknown as TypedDocumentString<SetFeedbackQuarantinedMutation, SetFeedbackQuarantinedMutationVariables>;
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
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
  url
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
      promoValidFrom
      promoValidTo
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
  url
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
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
  url
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
}
fragment PublicationStatusFields on PublicationStatus {
  state
  confirmationsReceived
  confirmationsRequired
  verified
}`) as unknown as TypedDocumentString<MyEditsQuery, MyEditsQueryVariables>;
export const MyReviewsDocument = new TypedDocumentString(`
    query MyReviews($first: Int, $offset: Int) {
  myReviews(first: $first, offset: $offset) {
    totalCount
    items {
      stars
      text
      createdAt
      updatedAt
      hidden
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
}`) as unknown as TypedDocumentString<MyReviewsQuery, MyReviewsQueryVariables>;
export const SearchProductsDocument = new TypedDocumentString(`
    query SearchProducts($query: String!, $storeId: ID, $city: String, $categoryId: ID, $country: String, $sort: ProductSort, $first: Int, $offset: Int) {
  searchProducts(
    query: $query
    storeId: $storeId
    city: $city
    categoryId: $categoryId
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
  url
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
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
  url
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<ProductQuery, ProductQueryVariables>;
export const ProductLookupByCodeDocument = new TypedDocumentString(`
    query ProductLookupByCode($code: String!) {
  productLookupByCode(code: $code) {
    status
    product {
      ...ProductDetailFields
    }
    candidate {
      code
      name
      brandName
      category {
        id
        name
        slug
        path
      }
      unitBase
      netContentValue
      netContentUom
      image {
        url
        thumbnailUrl
        attribution
      }
      sourceUrl
      attribution
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<ProductLookupByCodeQuery, ProductLookupByCodeQueryVariables>;
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
  photos {
    id
    thumbnailUrl
  }
  externalImage {
    thumbnailUrl
    attribution
  }
}`) as unknown as TypedDocumentString<ProductSuggestionsQuery, ProductSuggestionsQueryVariables>;
export const CategoriesDocument = new TypedDocumentString(`
    query Categories {
  categories {
    id
    name
    slug
    path
    sortOrder
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<CreateProductMutation, CreateProductMutationVariables>;
export const CreateProductFromOffDocument = new TypedDocumentString(`
    mutation CreateProductFromOff($input: CreateProductFromOffInput!) {
  createProductFromOff(input: $input) {
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
  }
  photos {
    ...PhotoFields
  }
}`) as unknown as TypedDocumentString<CreateProductFromOffMutation, CreateProductFromOffMutationVariables>;
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
  url
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
  kind
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
  promoValidTo
}
fragment ProductFields on Product {
  id
  name
  catalogSource
  catalogAttribution
  externalImage {
    url
    thumbnailUrl
    attribution
  }
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
  reviewCount
  myReviewText
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
    promoValidFrom
    promoValidTo
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
export const FlagReviewDocument = new TypedDocumentString(`
    mutation FlagReview($recordId: ID!, $reason: String) {
  flagRecord(recordType: REVIEW, recordId: $recordId, reason: $reason) {
    flagCount
    hidden
  }
}
    `) as unknown as TypedDocumentString<FlagReviewMutation, FlagReviewMutationVariables>;
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
  url
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
    mutation RateProduct($productId: ID!, $stars: Int!) {
  rateProduct(productId: $productId, stars: $stars) {
    average
    count
  }
}
    `) as unknown as TypedDocumentString<RateProductMutation, RateProductMutationVariables>;
export const ProductReviewsDocument = new TypedDocumentString(`
    query ProductReviews($productId: ID!, $first: Int, $offset: Int) {
  productReviews(productId: $productId, first: $first, offset: $offset) {
    totalCount
    hasMore
    loginRequired
    items {
      ...ProductReviewFields
    }
  }
}
    fragment ProductReviewFields on ProductReview {
  id
  stars
  text
  authorPublicUid
  authorName
  createdAt
  updatedAt
  mine
}`) as unknown as TypedDocumentString<ProductReviewsQuery, ProductReviewsQueryVariables>;
export const SaveProductReviewTextDocument = new TypedDocumentString(`
    mutation SaveProductReviewText($productId: ID!, $text: String!) {
  saveProductReviewText(productId: $productId, text: $text) {
    stars
    text
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<SaveProductReviewTextMutation, SaveProductReviewTextMutationVariables>;
export const DeleteProductReviewTextDocument = new TypedDocumentString(`
    mutation DeleteProductReviewText($productId: ID!) {
  deleteProductReviewText(productId: $productId) {
    stars
    text
    updatedAt
  }
}
    `) as unknown as TypedDocumentString<DeleteProductReviewTextMutation, DeleteProductReviewTextMutationVariables>;
export const SubmitObservationsDocument = new TypedDocumentString(`
    mutation SubmitObservations($input: SubmitObservationsInput!) {
  submitObservations(input: $input) {
    id
    priceAmount
    currency
    unitPrice
    priceKind
    quantityBasis
    promoValidFrom
    promoValidTo
    observedAt
    status
  }
}
    `) as unknown as TypedDocumentString<SubmitObservationsMutation, SubmitObservationsMutationVariables>;
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
  url
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
  url
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
  url
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
  kind
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
  url
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
  url
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
export const ChainsDocument = new TypedDocumentString(`
    query Chains($query: String, $country: String, $first: Int) {
  chains(query: $query, country: $country, first: $first) {
    id
    name
    chainType
  }
}
    `) as unknown as TypedDocumentString<ChainsQuery, ChainsQueryVariables>;
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
    moderator
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
  kind
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
  kind
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
  kind
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