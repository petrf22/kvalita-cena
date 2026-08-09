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
  }
`);

export const productFieldsFragment = graphql(`
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
    verified
    editedByMe
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
    cheapestStore {
      ...StoreFields
    }
  }
`);
