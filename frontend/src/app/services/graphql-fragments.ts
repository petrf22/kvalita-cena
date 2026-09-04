import { graphql } from '../models/generated';

/**
 * Sdílené fragmenty polí přes všechny GraphQL služby — dřív byly `STORE_FIELDS`/`PHOTO_FIELDS`
 * duplicitně v `product-service.ts` i `store-service.ts`, teď existují jen tady. Codegen
 * (`npm run codegen`, viz `codegen.ts`) z nich generuje typy `StoreFieldsFragment` apod.
 * použité v `models/catalog.ts`.
 */

/** Pole obchodu bez fotek — pro řádky cen, výsledky hledání, geokódování, nearbyStores atd. */
export const storeFieldsFragment = graphql(`
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
`);

export const photoFieldsFragment = graphql(`
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
`);

/**
 * Profil přihlášeného uživatele (docs/soukromi.md, "Profil uživatele a viditelnost") — vždy
 * plný pohled vlastníka, `Viewer.profile` se nikdy nefiltruje podle `visibility`.
 */
export const profileFieldsFragment = graphql(`
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
`);

/**
 * Navíc oproti StoreFields — jen pro detail stránky obchodu, ať se fotky netahají všude, kde
 * se Store objeví (řádky cen, výsledky hledání, ...), stejný vzor jako u produktu.
 */
export const storeDetailFieldsFragment = graphql(`
  fragment StoreDetailFields on Store {
    ...StoreFields
    photos {
      ...PhotoFields
    }
  }
`);

/**
 * Přepočet do zobrazovací měny kurzem ČNB (docs/lokalizace.md, "Kurzovní lístek a zobrazovací
 * měna") — null vždy, když se nepřepočítalo (X-Display-Currency nedorazila, rovná se původní
 * měně, nebo pro daný den kurz neznáme). `displayCurrencyInterceptor` posílá hlavičku, appka
 * pak zobrazí `converted` místo originálu, když je vyplněný.
 */
export const convertedPriceFieldsFragment = graphql(`
  fragment ConvertedPriceFields on ConvertedPrice {
    amount
    currency
    rateDate
  }
`);

export const priceCurrentFieldsFragment = graphql(`
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
`);

export const productFieldsFragment = graphql(`
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
    catalogScope
    scopeChain {
      id
      name
      chainType
    }
    scopeStore {
      ...StoreFields
    }
    verified
    editedByMe
    prices {
      ...PriceCurrentFields
    }
  }
`);

/** Navíc oproti ProductFields — jen pro detail, aby hledání netahalo zbytečně moc. */
export const productDetailFieldsFragment = graphql(`
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
`);

export const productSummaryFieldsFragment = graphql(`
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
    catalogScope
    scopeChain {
      id
      name
      chainType
    }
    scopeStore {
      ...StoreFields
    }
    verified
    editedByMe
    # Jen thumbnailUrl (ne celý PhotoFields fragment) — tenhle fragment se tahá pro každý řádek
    # výsledků hledání/zápisu ceny, kde stačí miniatura (shared/product-thumb.ts). Priorita
    # zdroje: vlastní fotka > OFF > zástupná ikona, stejné pravidlo jako na detailu zboží.
    photos {
      id
      thumbnailUrl
    }
    externalImage {
      thumbnailUrl
      attribution
    }
  }
`);

/**
 * Kdy se vlastní záznam propaguje globálně (docs/datovy-model.md, "Uživatelská vrstva nad
 * globálními daty") — jeden fragment pro všechny čtyři sekce "Moje příspěvky", ať klient
 * neumí ukázat protichůdný text na dvou různých obrazovkách.
 */
export const publicationStatusFieldsFragment = graphql(`
  fragment PublicationStatusFields on PublicationStatus {
    state
    confirmationsReceived
    confirmationsRequired
    verified
  }
`);

/** Jedna recenze pod zbožím (docs/soukromi.md, "Podepsaná recenze") — text je tu vždy vyplněný. */
export const productReviewFieldsFragment = graphql(`
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
`);

export const searchItemFieldsFragment = graphql(`
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
`);
