/* eslint-disable */
import * as types from './graphql';



/**
 * Map of all GraphQL operations in the project.
 *
 * This map has several performance disadvantages:
 * 1. It is not tree-shakeable, so it will include all operations in the project.
 * 2. It is not minifiable, so the string of a GraphQL query will be multiple times inside the bundle.
 * 3. It does not support dead code elimination, so it will add unused operations.
 *
 * Therefore it is highly recommended to use the babel or swc plugin for production.
 * Learn more about it here: https://the-guild.dev/graphql/codegen/plugins/presets/preset-client#reducing-bundle-size
 */
type Documents = {
    "\n      query Countries {\n        countries {\n          code\n          currency\n          defaultLocale\n        }\n      }\n    ": typeof types.CountriesDocument,
    "\n      mutation SubmitFeedback($input: FeedbackInput!) {\n        submitFeedback(input: $input) {\n          id\n        }\n      }\n    ": typeof types.SubmitFeedbackDocument,
    "\n      query FxInfo {\n        fxInfo {\n          displayCurrencies\n          latestRateDate\n          attribution\n        }\n      }\n    ": typeof types.FxInfoDocument,
    "\n  fragment StoreFields on Store {\n    id\n    name\n    street\n    city\n    postalCode\n    country\n    lat\n    lon\n    geoSource\n    ico\n    url\n    chain {\n      id\n      name\n      chainType\n    }\n    verified\n    editedByMe\n    pendingConfirmation\n  }\n": typeof types.StoreFieldsFragmentDoc,
    "\n  fragment PhotoFields on Photo {\n    id\n    url\n    thumbnailUrl\n    width\n    height\n    caption\n    mine\n    hidden\n    attribution\n    kind\n  }\n": typeof types.PhotoFieldsFragmentDoc,
    "\n  fragment ProfileFields on Profile {\n    firstName\n    lastName\n    phone\n    contactEmail\n    loginEmail\n    visibility\n    visibleFields {\n      field\n      audience\n    }\n    avatar {\n      ...PhotoFields\n    }\n  }\n": typeof types.ProfileFieldsFragmentDoc,
    "\n  fragment StoreDetailFields on Store {\n    ...StoreFields\n    photos {\n      ...PhotoFields\n    }\n  }\n": typeof types.StoreDetailFieldsFragmentDoc,
    "\n  fragment ConvertedPriceFields on ConvertedPrice {\n    amount\n    currency\n    rateDate\n  }\n": typeof types.ConvertedPriceFieldsFragmentDoc,
    "\n  fragment PriceCurrentFields on PriceCurrent {\n    store {\n      ...StoreFields\n    }\n    priceKind\n    unitPrice\n    priceAmount\n    currency\n    nObs\n    nEff\n    lastObservedAt\n    confidence\n    converted {\n      ...ConvertedPriceFields\n    }\n    promoValidTo\n  }\n": typeof types.PriceCurrentFieldsFragmentDoc,
    "\n  fragment ProductFields on Product {\n    id\n    name\n    catalogSource\n    catalogAttribution\n    externalImage {\n      url\n      thumbnailUrl\n      attribution\n    }\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    unitBase\n    netContentValue\n    netContentUom\n    netContentBase\n    piecesInPack\n    isVariableWeight\n    status\n    isGeneric\n    verified\n    editedByMe\n    prices {\n      ...PriceCurrentFields\n    }\n  }\n": typeof types.ProductFieldsFragmentDoc,
    "\n  fragment ProductDetailFields on Product {\n    ...ProductFields\n    gtin\n    stats {\n      observationCount\n      storeCount\n      lastObservedAt\n      bestPrice\n      bestUnitPrice\n      bestPriceCurrency\n      bestPriceConverted {\n        ...ConvertedPriceFields\n      }\n      cheapestStore {\n        ...StoreFields\n      }\n    }\n    quality {\n      average\n      count\n    }\n    myQualityRating\n    externalLinks {\n      kind\n      label\n      url\n      attribution\n    }\n    myPrices {\n      store {\n        ...StoreFields\n      }\n      priceKind\n      priceAmount\n      unitPrice\n      currency\n      observedAt\n      converted {\n        ...ConvertedPriceFields\n      }\n      promoValidFrom\n      promoValidTo\n    }\n    photos {\n      ...PhotoFields\n    }\n  }\n": typeof types.ProductDetailFieldsFragmentDoc,
    "\n  fragment ProductSummaryFields on Product {\n    id\n    name\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    isGeneric\n    verified\n    editedByMe\n  }\n": typeof types.ProductSummaryFieldsFragmentDoc,
    "\n  fragment PublicationStatusFields on PublicationStatus {\n    state\n    confirmationsReceived\n    confirmationsRequired\n    verified\n  }\n": typeof types.PublicationStatusFieldsFragmentDoc,
    "\n  fragment SearchItemFields on ProductSearchItem {\n    product {\n      ...ProductSummaryFields\n    }\n    observationCount\n    bestPrice\n    bestUnitPrice\n    currency\n    bestPriceObservations\n    lastObservedAt\n    qualityAverage\n    qualityCount\n    converted {\n      ...ConvertedPriceFields\n    }\n    convertedUnit {\n      ...ConvertedPriceFields\n    }\n    cheapestStore {\n      ...StoreFields\n    }\n  }\n": typeof types.SearchItemFieldsFragmentDoc,
    "\n      mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int, $kind: PhotoKind) {\n        updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder, kind: $kind) {\n          ...PhotoFields\n        }\n      }\n    ": typeof types.UpdatePhotoDocument,
    "\n      mutation DeletePhoto($id: ID!) {\n        deletePhoto(id: $id)\n      }\n    ": typeof types.DeletePhotoDocument,
    "\n      mutation FlagPhoto($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": typeof types.FlagPhotoDocument,
    "\n      query FlaggedRecords($recordType: RecordType, $first: Int, $offset: Int) {\n        flaggedRecords(recordType: $recordType, first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            recordId\n            flagCount\n            firstFlaggedAt\n            lastFlaggedAt\n            reasons\n            hidden\n            authorPublicUid\n            authorHandle\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n            photo {\n              ...PhotoFields\n            }\n          }\n        }\n      }\n    ": typeof types.FlaggedRecordsDocument,
    "\n      mutation ResolveFlags(\n        $recordType: RecordType!\n        $recordId: ID!\n        $resolution: FlagResolution!\n      ) {\n        resolveFlags(recordType: $recordType, recordId: $recordId, resolution: $resolution)\n      }\n    ": typeof types.ResolveFlagsDocument,
    "\n      query ModerationObservations($productId: ID, $storeId: ID, $first: Int, $offset: Int) {\n        moderationObservations(\n          productId: $productId\n          storeId: $storeId\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          items {\n            authorPublicUid\n            authorHandle\n            observation {\n              id\n              priceAmount\n              currency\n              priceKind\n              unitPrice\n              observedAt\n              status\n              product {\n                ...ProductSummaryFields\n              }\n              store {\n                ...StoreFields\n              }\n            }\n          }\n        }\n      }\n    ": typeof types.ModerationObservationsDocument,
    "\n      mutation SetObservationRejected($id: ID!, $rejected: Boolean!, $reason: String) {\n        setObservationRejected(id: $id, rejected: $rejected, reason: $reason) {\n          id\n          status\n        }\n      }\n    ": typeof types.SetObservationRejectedDocument,
    "\n      mutation SetUserSuspended($publicUid: ID!, $suspended: Boolean!, $reason: String) {\n        setUserSuspended(publicUid: $publicUid, suspended: $suspended, reason: $reason)\n      }\n    ": typeof types.SetUserSuspendedDocument,
    "\n      query FeedbackItems($handled: Boolean, $first: Int, $offset: Int) {\n        feedbackItems(handled: $handled, first: $first, offset: $offset) {\n          totalCount\n          items {\n            id\n            category\n            message\n            contactEmail\n            clientKind\n            appVersion\n            platformInfo\n            locale\n            country\n            pageRef\n            diagnostics\n            createdAt\n            handled\n            handledNote\n            authorPublicUid\n            authorHandle\n          }\n        }\n      }\n    ": typeof types.FeedbackItemsDocument,
    "\n      mutation SetFeedbackHandled($id: ID!, $handled: Boolean!, $note: String) {\n        setFeedbackHandled(id: $id, handled: $handled, note: $note)\n      }\n    ": typeof types.SetFeedbackHandledDocument,
    "\n      query MyProducts($first: Int, $offset: Int) {\n        myProducts(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n          }\n        }\n      }\n    ": typeof types.MyProductsDocument,
    "\n      query MyStores($first: Int, $offset: Int) {\n        myStores(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": typeof types.MyStoresDocument,
    "\n      query MyObservations($first: Int, $offset: Int) {\n        myObservations(first: $first, offset: $offset) {\n          totalCount\n          items {\n            priceKind\n            priceAmount\n            unitPrice\n            currency\n            promoValidFrom\n            promoValidTo\n            observedAt\n            createdAt\n            converted {\n              ...ConvertedPriceFields\n            }\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": typeof types.MyObservationsDocument,
    "\n      query MyEdits($first: Int, $offset: Int) {\n        myEdits(first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            updatedAt\n            changedFields\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": typeof types.MyEditsDocument,
    "\n      query SearchProducts(\n        $query: String!\n        $storeId: ID\n        $city: String\n        $categoryId: ID\n        $country: String\n        $sort: ProductSort\n        $first: Int\n        $offset: Int\n      ) {\n        searchProducts(\n          query: $query\n          storeId: $storeId\n          city: $city\n          categoryId: $categoryId\n          country: $country\n          sort: $sort\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          hasMore\n          items {\n            ...SearchItemFields\n          }\n        }\n      }\n    ": typeof types.SearchProductsDocument,
    "\n      query SearchFacets($country: String) {\n        searchFacets(country: $country) {\n          cities\n          stores {\n            ...StoreFields\n          }\n        }\n      }\n    ": typeof types.SearchFacetsDocument,
    "\n      query Product($id: ID!) {\n        product(id: $id) {\n          ...ProductDetailFields\n        }\n      }\n    ": typeof types.ProductDocument,
    "\n      query ProductLookupByCode($code: String!) {\n        productLookupByCode(code: $code) {\n          status\n          product {\n            ...ProductDetailFields\n          }\n          candidate {\n            code\n            name\n            brandName\n            category {\n              id\n              name\n              slug\n              path\n            }\n            unitBase\n            netContentValue\n            netContentUom\n            image {\n              url\n              thumbnailUrl\n              attribution\n            }\n            sourceUrl\n            attribution\n          }\n        }\n      }\n    ": typeof types.ProductLookupByCodeDocument,
    "\n      query ProductSuggestions($name: String!, $first: Int) {\n        productSuggestions(name: $name, first: $first) {\n          ...ProductSummaryFields\n        }\n      }\n    ": typeof types.ProductSuggestionsDocument,
    "\n      query Categories {\n        categories {\n          id\n          name\n          slug\n          path\n          sortOrder\n        }\n      }\n    ": typeof types.CategoriesDocument,
    "\n      mutation CreateProduct($input: CreateProductInput!) {\n        createProduct(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": typeof types.CreateProductDocument,
    "\n      mutation CreateProductFromOff($input: CreateProductFromOffInput!) {\n        createProductFromOff(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": typeof types.CreateProductFromOffDocument,
    "\n      mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {\n        updateProduct(id: $id, input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": typeof types.UpdateProductDocument,
    "\n      mutation FlagProduct($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": typeof types.FlagProductDocument,
    "\n      query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {\n        priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {\n          priceKind\n          days\n          currency\n          displayCurrency\n          rateAttribution\n          store {\n            ...StoreFields\n          }\n          points {\n            day\n            priceAmount\n            unitPrice\n            nObs\n            storeCount\n            convertedUnitPrice\n            convertedPriceAmount\n          }\n        }\n      }\n    ": typeof types.PriceHistoryDocument,
    "\n      mutation RateProduct($productId: ID!, $stars: Int!) {\n        rateProduct(productId: $productId, stars: $stars) {\n          average\n          count\n        }\n      }\n    ": typeof types.RateProductDocument,
    "\n      mutation SubmitObservations($input: SubmitObservationsInput!) {\n        submitObservations(input: $input) {\n          id\n          priceAmount\n          currency\n          unitPrice\n          priceKind\n          quantityBasis\n          promoValidFrom\n          promoValidTo\n          observedAt\n          status\n        }\n      }\n    ": typeof types.SubmitObservationsDocument,
    "\n      query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {\n        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {\n          ...StoreFields\n        }\n      }\n    ": typeof types.NearbyStoresDocument,
    "\n      query SearchStores($query: String, $city: String, $first: Int) {\n        searchStores(query: $query, city: $city, first: $first) {\n          totalCount\n          hasMore\n          items {\n            ...StoreFields\n          }\n        }\n      }\n    ": typeof types.SearchStoresDocument,
    "\n      query Store($id: ID!) {\n        store(id: $id) {\n          ...StoreDetailFields\n        }\n      }\n    ": typeof types.StoreDocument,
    "\n      mutation CreateStore($input: CreateStoreInput!) {\n        createStore(input: $input) {\n          ...StoreFields\n        }\n      }\n    ": typeof types.CreateStoreDocument,
    "\n      mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {\n        updateStore(id: $id, input: $input) {\n          ...StoreFields\n        }\n      }\n    ": typeof types.UpdateStoreDocument,
    "\n      mutation FlagStore($recordId: ID!, $reason: String) {\n        flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": typeof types.FlagStoreDocument,
    "\n      query GeocodeAddress($street: String, $city: String!, $postalCode: String) {\n        geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {\n          attribution\n          candidates {\n            lat\n            lon\n            displayName\n            osmRef\n          }\n        }\n      }\n    ": typeof types.GeocodeAddressDocument,
    "\n      query ReverseGeocode($lat: Float!, $lon: Float!) {\n        reverseGeocode(lat: $lat, lon: $lon) {\n          street\n          city\n          postalCode\n          country\n          osmRef\n          attribution\n        }\n      }\n    ": typeof types.ReverseGeocodeDocument,
    "\n      query Chains($query: String, $country: String, $first: Int) {\n        chains(query: $query, country: $country, first: $first) {\n          id\n          name\n          chainType\n        }\n      }\n    ": typeof types.ChainsDocument,
    "\n      query CompanyByIco($ico: String!) {\n        companyByIco(ico: $ico) {\n          ico\n          name\n          street\n          city\n          postalCode\n        }\n      }\n    ": typeof types.CompanyByIcoDocument,
    "\n      query Me {\n        me {\n          publicHandle\n          displayName\n          createdAt\n          trusted\n          moderator\n          locale\n          country\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": typeof types.MeDocument,
    "\n      mutation SetLocale($locale: String!, $country: String) {\n        setLocale(locale: $locale, country: $country) {\n          locale\n          country\n        }\n      }\n    ": typeof types.SetLocaleDocument,
    "\n      mutation UpdateProfile($input: UpdateProfileInput!) {\n        updateProfile(input: $input) {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": typeof types.UpdateProfileDocument,
    "\n      mutation DeleteAvatar {\n        deleteAvatar {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": typeof types.DeleteAvatarDocument,
};
const documents: Documents = {
    "\n      query Countries {\n        countries {\n          code\n          currency\n          defaultLocale\n        }\n      }\n    ": types.CountriesDocument,
    "\n      mutation SubmitFeedback($input: FeedbackInput!) {\n        submitFeedback(input: $input) {\n          id\n        }\n      }\n    ": types.SubmitFeedbackDocument,
    "\n      query FxInfo {\n        fxInfo {\n          displayCurrencies\n          latestRateDate\n          attribution\n        }\n      }\n    ": types.FxInfoDocument,
    "\n  fragment StoreFields on Store {\n    id\n    name\n    street\n    city\n    postalCode\n    country\n    lat\n    lon\n    geoSource\n    ico\n    url\n    chain {\n      id\n      name\n      chainType\n    }\n    verified\n    editedByMe\n    pendingConfirmation\n  }\n": types.StoreFieldsFragmentDoc,
    "\n  fragment PhotoFields on Photo {\n    id\n    url\n    thumbnailUrl\n    width\n    height\n    caption\n    mine\n    hidden\n    attribution\n    kind\n  }\n": types.PhotoFieldsFragmentDoc,
    "\n  fragment ProfileFields on Profile {\n    firstName\n    lastName\n    phone\n    contactEmail\n    loginEmail\n    visibility\n    visibleFields {\n      field\n      audience\n    }\n    avatar {\n      ...PhotoFields\n    }\n  }\n": types.ProfileFieldsFragmentDoc,
    "\n  fragment StoreDetailFields on Store {\n    ...StoreFields\n    photos {\n      ...PhotoFields\n    }\n  }\n": types.StoreDetailFieldsFragmentDoc,
    "\n  fragment ConvertedPriceFields on ConvertedPrice {\n    amount\n    currency\n    rateDate\n  }\n": types.ConvertedPriceFieldsFragmentDoc,
    "\n  fragment PriceCurrentFields on PriceCurrent {\n    store {\n      ...StoreFields\n    }\n    priceKind\n    unitPrice\n    priceAmount\n    currency\n    nObs\n    nEff\n    lastObservedAt\n    confidence\n    converted {\n      ...ConvertedPriceFields\n    }\n    promoValidTo\n  }\n": types.PriceCurrentFieldsFragmentDoc,
    "\n  fragment ProductFields on Product {\n    id\n    name\n    catalogSource\n    catalogAttribution\n    externalImage {\n      url\n      thumbnailUrl\n      attribution\n    }\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    unitBase\n    netContentValue\n    netContentUom\n    netContentBase\n    piecesInPack\n    isVariableWeight\n    status\n    isGeneric\n    verified\n    editedByMe\n    prices {\n      ...PriceCurrentFields\n    }\n  }\n": types.ProductFieldsFragmentDoc,
    "\n  fragment ProductDetailFields on Product {\n    ...ProductFields\n    gtin\n    stats {\n      observationCount\n      storeCount\n      lastObservedAt\n      bestPrice\n      bestUnitPrice\n      bestPriceCurrency\n      bestPriceConverted {\n        ...ConvertedPriceFields\n      }\n      cheapestStore {\n        ...StoreFields\n      }\n    }\n    quality {\n      average\n      count\n    }\n    myQualityRating\n    externalLinks {\n      kind\n      label\n      url\n      attribution\n    }\n    myPrices {\n      store {\n        ...StoreFields\n      }\n      priceKind\n      priceAmount\n      unitPrice\n      currency\n      observedAt\n      converted {\n        ...ConvertedPriceFields\n      }\n      promoValidFrom\n      promoValidTo\n    }\n    photos {\n      ...PhotoFields\n    }\n  }\n": types.ProductDetailFieldsFragmentDoc,
    "\n  fragment ProductSummaryFields on Product {\n    id\n    name\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    isGeneric\n    verified\n    editedByMe\n  }\n": types.ProductSummaryFieldsFragmentDoc,
    "\n  fragment PublicationStatusFields on PublicationStatus {\n    state\n    confirmationsReceived\n    confirmationsRequired\n    verified\n  }\n": types.PublicationStatusFieldsFragmentDoc,
    "\n  fragment SearchItemFields on ProductSearchItem {\n    product {\n      ...ProductSummaryFields\n    }\n    observationCount\n    bestPrice\n    bestUnitPrice\n    currency\n    bestPriceObservations\n    lastObservedAt\n    qualityAverage\n    qualityCount\n    converted {\n      ...ConvertedPriceFields\n    }\n    convertedUnit {\n      ...ConvertedPriceFields\n    }\n    cheapestStore {\n      ...StoreFields\n    }\n  }\n": types.SearchItemFieldsFragmentDoc,
    "\n      mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int, $kind: PhotoKind) {\n        updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder, kind: $kind) {\n          ...PhotoFields\n        }\n      }\n    ": types.UpdatePhotoDocument,
    "\n      mutation DeletePhoto($id: ID!) {\n        deletePhoto(id: $id)\n      }\n    ": types.DeletePhotoDocument,
    "\n      mutation FlagPhoto($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": types.FlagPhotoDocument,
    "\n      query FlaggedRecords($recordType: RecordType, $first: Int, $offset: Int) {\n        flaggedRecords(recordType: $recordType, first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            recordId\n            flagCount\n            firstFlaggedAt\n            lastFlaggedAt\n            reasons\n            hidden\n            authorPublicUid\n            authorHandle\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n            photo {\n              ...PhotoFields\n            }\n          }\n        }\n      }\n    ": types.FlaggedRecordsDocument,
    "\n      mutation ResolveFlags(\n        $recordType: RecordType!\n        $recordId: ID!\n        $resolution: FlagResolution!\n      ) {\n        resolveFlags(recordType: $recordType, recordId: $recordId, resolution: $resolution)\n      }\n    ": types.ResolveFlagsDocument,
    "\n      query ModerationObservations($productId: ID, $storeId: ID, $first: Int, $offset: Int) {\n        moderationObservations(\n          productId: $productId\n          storeId: $storeId\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          items {\n            authorPublicUid\n            authorHandle\n            observation {\n              id\n              priceAmount\n              currency\n              priceKind\n              unitPrice\n              observedAt\n              status\n              product {\n                ...ProductSummaryFields\n              }\n              store {\n                ...StoreFields\n              }\n            }\n          }\n        }\n      }\n    ": types.ModerationObservationsDocument,
    "\n      mutation SetObservationRejected($id: ID!, $rejected: Boolean!, $reason: String) {\n        setObservationRejected(id: $id, rejected: $rejected, reason: $reason) {\n          id\n          status\n        }\n      }\n    ": types.SetObservationRejectedDocument,
    "\n      mutation SetUserSuspended($publicUid: ID!, $suspended: Boolean!, $reason: String) {\n        setUserSuspended(publicUid: $publicUid, suspended: $suspended, reason: $reason)\n      }\n    ": types.SetUserSuspendedDocument,
    "\n      query FeedbackItems($handled: Boolean, $first: Int, $offset: Int) {\n        feedbackItems(handled: $handled, first: $first, offset: $offset) {\n          totalCount\n          items {\n            id\n            category\n            message\n            contactEmail\n            clientKind\n            appVersion\n            platformInfo\n            locale\n            country\n            pageRef\n            diagnostics\n            createdAt\n            handled\n            handledNote\n            authorPublicUid\n            authorHandle\n          }\n        }\n      }\n    ": types.FeedbackItemsDocument,
    "\n      mutation SetFeedbackHandled($id: ID!, $handled: Boolean!, $note: String) {\n        setFeedbackHandled(id: $id, handled: $handled, note: $note)\n      }\n    ": types.SetFeedbackHandledDocument,
    "\n      query MyProducts($first: Int, $offset: Int) {\n        myProducts(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n          }\n        }\n      }\n    ": types.MyProductsDocument,
    "\n      query MyStores($first: Int, $offset: Int) {\n        myStores(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": types.MyStoresDocument,
    "\n      query MyObservations($first: Int, $offset: Int) {\n        myObservations(first: $first, offset: $offset) {\n          totalCount\n          items {\n            priceKind\n            priceAmount\n            unitPrice\n            currency\n            promoValidFrom\n            promoValidTo\n            observedAt\n            createdAt\n            converted {\n              ...ConvertedPriceFields\n            }\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": types.MyObservationsDocument,
    "\n      query MyEdits($first: Int, $offset: Int) {\n        myEdits(first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            updatedAt\n            changedFields\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    ": types.MyEditsDocument,
    "\n      query SearchProducts(\n        $query: String!\n        $storeId: ID\n        $city: String\n        $categoryId: ID\n        $country: String\n        $sort: ProductSort\n        $first: Int\n        $offset: Int\n      ) {\n        searchProducts(\n          query: $query\n          storeId: $storeId\n          city: $city\n          categoryId: $categoryId\n          country: $country\n          sort: $sort\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          hasMore\n          items {\n            ...SearchItemFields\n          }\n        }\n      }\n    ": types.SearchProductsDocument,
    "\n      query SearchFacets($country: String) {\n        searchFacets(country: $country) {\n          cities\n          stores {\n            ...StoreFields\n          }\n        }\n      }\n    ": types.SearchFacetsDocument,
    "\n      query Product($id: ID!) {\n        product(id: $id) {\n          ...ProductDetailFields\n        }\n      }\n    ": types.ProductDocument,
    "\n      query ProductLookupByCode($code: String!) {\n        productLookupByCode(code: $code) {\n          status\n          product {\n            ...ProductDetailFields\n          }\n          candidate {\n            code\n            name\n            brandName\n            category {\n              id\n              name\n              slug\n              path\n            }\n            unitBase\n            netContentValue\n            netContentUom\n            image {\n              url\n              thumbnailUrl\n              attribution\n            }\n            sourceUrl\n            attribution\n          }\n        }\n      }\n    ": types.ProductLookupByCodeDocument,
    "\n      query ProductSuggestions($name: String!, $first: Int) {\n        productSuggestions(name: $name, first: $first) {\n          ...ProductSummaryFields\n        }\n      }\n    ": types.ProductSuggestionsDocument,
    "\n      query Categories {\n        categories {\n          id\n          name\n          slug\n          path\n          sortOrder\n        }\n      }\n    ": types.CategoriesDocument,
    "\n      mutation CreateProduct($input: CreateProductInput!) {\n        createProduct(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": types.CreateProductDocument,
    "\n      mutation CreateProductFromOff($input: CreateProductFromOffInput!) {\n        createProductFromOff(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": types.CreateProductFromOffDocument,
    "\n      mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {\n        updateProduct(id: $id, input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    ": types.UpdateProductDocument,
    "\n      mutation FlagProduct($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": types.FlagProductDocument,
    "\n      query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {\n        priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {\n          priceKind\n          days\n          currency\n          displayCurrency\n          rateAttribution\n          store {\n            ...StoreFields\n          }\n          points {\n            day\n            priceAmount\n            unitPrice\n            nObs\n            storeCount\n            convertedUnitPrice\n            convertedPriceAmount\n          }\n        }\n      }\n    ": types.PriceHistoryDocument,
    "\n      mutation RateProduct($productId: ID!, $stars: Int!) {\n        rateProduct(productId: $productId, stars: $stars) {\n          average\n          count\n        }\n      }\n    ": types.RateProductDocument,
    "\n      mutation SubmitObservations($input: SubmitObservationsInput!) {\n        submitObservations(input: $input) {\n          id\n          priceAmount\n          currency\n          unitPrice\n          priceKind\n          quantityBasis\n          promoValidFrom\n          promoValidTo\n          observedAt\n          status\n        }\n      }\n    ": types.SubmitObservationsDocument,
    "\n      query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {\n        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {\n          ...StoreFields\n        }\n      }\n    ": types.NearbyStoresDocument,
    "\n      query SearchStores($query: String, $city: String, $first: Int) {\n        searchStores(query: $query, city: $city, first: $first) {\n          totalCount\n          hasMore\n          items {\n            ...StoreFields\n          }\n        }\n      }\n    ": types.SearchStoresDocument,
    "\n      query Store($id: ID!) {\n        store(id: $id) {\n          ...StoreDetailFields\n        }\n      }\n    ": types.StoreDocument,
    "\n      mutation CreateStore($input: CreateStoreInput!) {\n        createStore(input: $input) {\n          ...StoreFields\n        }\n      }\n    ": types.CreateStoreDocument,
    "\n      mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {\n        updateStore(id: $id, input: $input) {\n          ...StoreFields\n        }\n      }\n    ": types.UpdateStoreDocument,
    "\n      mutation FlagStore($recordId: ID!, $reason: String) {\n        flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    ": types.FlagStoreDocument,
    "\n      query GeocodeAddress($street: String, $city: String!, $postalCode: String) {\n        geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {\n          attribution\n          candidates {\n            lat\n            lon\n            displayName\n            osmRef\n          }\n        }\n      }\n    ": types.GeocodeAddressDocument,
    "\n      query ReverseGeocode($lat: Float!, $lon: Float!) {\n        reverseGeocode(lat: $lat, lon: $lon) {\n          street\n          city\n          postalCode\n          country\n          osmRef\n          attribution\n        }\n      }\n    ": types.ReverseGeocodeDocument,
    "\n      query Chains($query: String, $country: String, $first: Int) {\n        chains(query: $query, country: $country, first: $first) {\n          id\n          name\n          chainType\n        }\n      }\n    ": types.ChainsDocument,
    "\n      query CompanyByIco($ico: String!) {\n        companyByIco(ico: $ico) {\n          ico\n          name\n          street\n          city\n          postalCode\n        }\n      }\n    ": types.CompanyByIcoDocument,
    "\n      query Me {\n        me {\n          publicHandle\n          displayName\n          createdAt\n          trusted\n          moderator\n          locale\n          country\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": types.MeDocument,
    "\n      mutation SetLocale($locale: String!, $country: String) {\n        setLocale(locale: $locale, country: $country) {\n          locale\n          country\n        }\n      }\n    ": types.SetLocaleDocument,
    "\n      mutation UpdateProfile($input: UpdateProfileInput!) {\n        updateProfile(input: $input) {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": types.UpdateProfileDocument,
    "\n      mutation DeleteAvatar {\n        deleteAvatar {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    ": types.DeleteAvatarDocument,
};

/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Countries {\n        countries {\n          code\n          currency\n          defaultLocale\n        }\n      }\n    "): typeof import('./graphql').CountriesDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SubmitFeedback($input: FeedbackInput!) {\n        submitFeedback(input: $input) {\n          id\n        }\n      }\n    "): typeof import('./graphql').SubmitFeedbackDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query FxInfo {\n        fxInfo {\n          displayCurrencies\n          latestRateDate\n          attribution\n        }\n      }\n    "): typeof import('./graphql').FxInfoDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment StoreFields on Store {\n    id\n    name\n    street\n    city\n    postalCode\n    country\n    lat\n    lon\n    geoSource\n    ico\n    url\n    chain {\n      id\n      name\n      chainType\n    }\n    verified\n    editedByMe\n    pendingConfirmation\n  }\n"): typeof import('./graphql').StoreFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment PhotoFields on Photo {\n    id\n    url\n    thumbnailUrl\n    width\n    height\n    caption\n    mine\n    hidden\n    attribution\n    kind\n  }\n"): typeof import('./graphql').PhotoFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment ProfileFields on Profile {\n    firstName\n    lastName\n    phone\n    contactEmail\n    loginEmail\n    visibility\n    visibleFields {\n      field\n      audience\n    }\n    avatar {\n      ...PhotoFields\n    }\n  }\n"): typeof import('./graphql').ProfileFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment StoreDetailFields on Store {\n    ...StoreFields\n    photos {\n      ...PhotoFields\n    }\n  }\n"): typeof import('./graphql').StoreDetailFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment ConvertedPriceFields on ConvertedPrice {\n    amount\n    currency\n    rateDate\n  }\n"): typeof import('./graphql').ConvertedPriceFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment PriceCurrentFields on PriceCurrent {\n    store {\n      ...StoreFields\n    }\n    priceKind\n    unitPrice\n    priceAmount\n    currency\n    nObs\n    nEff\n    lastObservedAt\n    confidence\n    converted {\n      ...ConvertedPriceFields\n    }\n    promoValidTo\n  }\n"): typeof import('./graphql').PriceCurrentFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment ProductFields on Product {\n    id\n    name\n    catalogSource\n    catalogAttribution\n    externalImage {\n      url\n      thumbnailUrl\n      attribution\n    }\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    unitBase\n    netContentValue\n    netContentUom\n    netContentBase\n    piecesInPack\n    isVariableWeight\n    status\n    isGeneric\n    verified\n    editedByMe\n    prices {\n      ...PriceCurrentFields\n    }\n  }\n"): typeof import('./graphql').ProductFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment ProductDetailFields on Product {\n    ...ProductFields\n    gtin\n    stats {\n      observationCount\n      storeCount\n      lastObservedAt\n      bestPrice\n      bestUnitPrice\n      bestPriceCurrency\n      bestPriceConverted {\n        ...ConvertedPriceFields\n      }\n      cheapestStore {\n        ...StoreFields\n      }\n    }\n    quality {\n      average\n      count\n    }\n    myQualityRating\n    externalLinks {\n      kind\n      label\n      url\n      attribution\n    }\n    myPrices {\n      store {\n        ...StoreFields\n      }\n      priceKind\n      priceAmount\n      unitPrice\n      currency\n      observedAt\n      converted {\n        ...ConvertedPriceFields\n      }\n      promoValidFrom\n      promoValidTo\n    }\n    photos {\n      ...PhotoFields\n    }\n  }\n"): typeof import('./graphql').ProductDetailFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment ProductSummaryFields on Product {\n    id\n    name\n    brand {\n      id\n      name\n      slug\n    }\n    category {\n      id\n      name\n      slug\n      path\n    }\n    isGeneric\n    verified\n    editedByMe\n  }\n"): typeof import('./graphql').ProductSummaryFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment PublicationStatusFields on PublicationStatus {\n    state\n    confirmationsReceived\n    confirmationsRequired\n    verified\n  }\n"): typeof import('./graphql').PublicationStatusFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  fragment SearchItemFields on ProductSearchItem {\n    product {\n      ...ProductSummaryFields\n    }\n    observationCount\n    bestPrice\n    bestUnitPrice\n    currency\n    bestPriceObservations\n    lastObservedAt\n    qualityAverage\n    qualityCount\n    converted {\n      ...ConvertedPriceFields\n    }\n    convertedUnit {\n      ...ConvertedPriceFields\n    }\n    cheapestStore {\n      ...StoreFields\n    }\n  }\n"): typeof import('./graphql').SearchItemFieldsFragmentDoc;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation UpdatePhoto($id: ID!, $caption: String, $sortOrder: Int, $kind: PhotoKind) {\n        updatePhoto(id: $id, caption: $caption, sortOrder: $sortOrder, kind: $kind) {\n          ...PhotoFields\n        }\n      }\n    "): typeof import('./graphql').UpdatePhotoDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation DeletePhoto($id: ID!) {\n        deletePhoto(id: $id)\n      }\n    "): typeof import('./graphql').DeletePhotoDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation FlagPhoto($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PHOTO, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    "): typeof import('./graphql').FlagPhotoDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query FlaggedRecords($recordType: RecordType, $first: Int, $offset: Int) {\n        flaggedRecords(recordType: $recordType, first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            recordId\n            flagCount\n            firstFlaggedAt\n            lastFlaggedAt\n            reasons\n            hidden\n            authorPublicUid\n            authorHandle\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n            photo {\n              ...PhotoFields\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').FlaggedRecordsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation ResolveFlags(\n        $recordType: RecordType!\n        $recordId: ID!\n        $resolution: FlagResolution!\n      ) {\n        resolveFlags(recordType: $recordType, recordId: $recordId, resolution: $resolution)\n      }\n    "): typeof import('./graphql').ResolveFlagsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query ModerationObservations($productId: ID, $storeId: ID, $first: Int, $offset: Int) {\n        moderationObservations(\n          productId: $productId\n          storeId: $storeId\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          items {\n            authorPublicUid\n            authorHandle\n            observation {\n              id\n              priceAmount\n              currency\n              priceKind\n              unitPrice\n              observedAt\n              status\n              product {\n                ...ProductSummaryFields\n              }\n              store {\n                ...StoreFields\n              }\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').ModerationObservationsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SetObservationRejected($id: ID!, $rejected: Boolean!, $reason: String) {\n        setObservationRejected(id: $id, rejected: $rejected, reason: $reason) {\n          id\n          status\n        }\n      }\n    "): typeof import('./graphql').SetObservationRejectedDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SetUserSuspended($publicUid: ID!, $suspended: Boolean!, $reason: String) {\n        setUserSuspended(publicUid: $publicUid, suspended: $suspended, reason: $reason)\n      }\n    "): typeof import('./graphql').SetUserSuspendedDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query FeedbackItems($handled: Boolean, $first: Int, $offset: Int) {\n        feedbackItems(handled: $handled, first: $first, offset: $offset) {\n          totalCount\n          items {\n            id\n            category\n            message\n            contactEmail\n            clientKind\n            appVersion\n            platformInfo\n            locale\n            country\n            pageRef\n            diagnostics\n            createdAt\n            handled\n            handledNote\n            authorPublicUid\n            authorHandle\n          }\n        }\n      }\n    "): typeof import('./graphql').FeedbackItemsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SetFeedbackHandled($id: ID!, $handled: Boolean!, $note: String) {\n        setFeedbackHandled(id: $id, handled: $handled, note: $note)\n      }\n    "): typeof import('./graphql').SetFeedbackHandledDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query MyProducts($first: Int, $offset: Int) {\n        myProducts(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').MyProductsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query MyStores($first: Int, $offset: Int) {\n        myStores(first: $first, offset: $offset) {\n          totalCount\n          items {\n            createdAt\n            publication {\n              ...PublicationStatusFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').MyStoresDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query MyObservations($first: Int, $offset: Int) {\n        myObservations(first: $first, offset: $offset) {\n          totalCount\n          items {\n            priceKind\n            priceAmount\n            unitPrice\n            currency\n            promoValidFrom\n            promoValidTo\n            observedAt\n            createdAt\n            converted {\n              ...ConvertedPriceFields\n            }\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').MyObservationsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query MyEdits($first: Int, $offset: Int) {\n        myEdits(first: $first, offset: $offset) {\n          totalCount\n          items {\n            recordType\n            updatedAt\n            changedFields\n            publication {\n              ...PublicationStatusFields\n            }\n            product {\n              ...ProductSummaryFields\n            }\n            store {\n              ...StoreFields\n            }\n          }\n        }\n      }\n    "): typeof import('./graphql').MyEditsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query SearchProducts(\n        $query: String!\n        $storeId: ID\n        $city: String\n        $categoryId: ID\n        $country: String\n        $sort: ProductSort\n        $first: Int\n        $offset: Int\n      ) {\n        searchProducts(\n          query: $query\n          storeId: $storeId\n          city: $city\n          categoryId: $categoryId\n          country: $country\n          sort: $sort\n          first: $first\n          offset: $offset\n        ) {\n          totalCount\n          hasMore\n          items {\n            ...SearchItemFields\n          }\n        }\n      }\n    "): typeof import('./graphql').SearchProductsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query SearchFacets($country: String) {\n        searchFacets(country: $country) {\n          cities\n          stores {\n            ...StoreFields\n          }\n        }\n      }\n    "): typeof import('./graphql').SearchFacetsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Product($id: ID!) {\n        product(id: $id) {\n          ...ProductDetailFields\n        }\n      }\n    "): typeof import('./graphql').ProductDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query ProductLookupByCode($code: String!) {\n        productLookupByCode(code: $code) {\n          status\n          product {\n            ...ProductDetailFields\n          }\n          candidate {\n            code\n            name\n            brandName\n            category {\n              id\n              name\n              slug\n              path\n            }\n            unitBase\n            netContentValue\n            netContentUom\n            image {\n              url\n              thumbnailUrl\n              attribution\n            }\n            sourceUrl\n            attribution\n          }\n        }\n      }\n    "): typeof import('./graphql').ProductLookupByCodeDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query ProductSuggestions($name: String!, $first: Int) {\n        productSuggestions(name: $name, first: $first) {\n          ...ProductSummaryFields\n        }\n      }\n    "): typeof import('./graphql').ProductSuggestionsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Categories {\n        categories {\n          id\n          name\n          slug\n          path\n          sortOrder\n        }\n      }\n    "): typeof import('./graphql').CategoriesDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation CreateProduct($input: CreateProductInput!) {\n        createProduct(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    "): typeof import('./graphql').CreateProductDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation CreateProductFromOff($input: CreateProductFromOffInput!) {\n        createProductFromOff(input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    "): typeof import('./graphql').CreateProductFromOffDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation UpdateProduct($id: ID!, $input: UpdateProductInput!) {\n        updateProduct(id: $id, input: $input) {\n          ...ProductDetailFields\n        }\n      }\n    "): typeof import('./graphql').UpdateProductDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation FlagProduct($recordId: ID!, $reason: String) {\n        flagRecord(recordType: PRODUCT, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    "): typeof import('./graphql').FlagProductDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query PriceHistory($productId: ID!, $priceKind: PriceKind, $days: Int) {\n        priceHistory(productId: $productId, priceKind: $priceKind, days: $days) {\n          priceKind\n          days\n          currency\n          displayCurrency\n          rateAttribution\n          store {\n            ...StoreFields\n          }\n          points {\n            day\n            priceAmount\n            unitPrice\n            nObs\n            storeCount\n            convertedUnitPrice\n            convertedPriceAmount\n          }\n        }\n      }\n    "): typeof import('./graphql').PriceHistoryDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation RateProduct($productId: ID!, $stars: Int!) {\n        rateProduct(productId: $productId, stars: $stars) {\n          average\n          count\n        }\n      }\n    "): typeof import('./graphql').RateProductDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SubmitObservations($input: SubmitObservationsInput!) {\n        submitObservations(input: $input) {\n          id\n          priceAmount\n          currency\n          unitPrice\n          priceKind\n          quantityBasis\n          promoValidFrom\n          promoValidTo\n          observedAt\n          status\n        }\n      }\n    "): typeof import('./graphql').SubmitObservationsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query NearbyStores($lat: Float!, $lon: Float!, $radiusKm: Float) {\n        nearbyStores(lat: $lat, lon: $lon, radiusKm: $radiusKm) {\n          ...StoreFields\n        }\n      }\n    "): typeof import('./graphql').NearbyStoresDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query SearchStores($query: String, $city: String, $first: Int) {\n        searchStores(query: $query, city: $city, first: $first) {\n          totalCount\n          hasMore\n          items {\n            ...StoreFields\n          }\n        }\n      }\n    "): typeof import('./graphql').SearchStoresDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Store($id: ID!) {\n        store(id: $id) {\n          ...StoreDetailFields\n        }\n      }\n    "): typeof import('./graphql').StoreDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation CreateStore($input: CreateStoreInput!) {\n        createStore(input: $input) {\n          ...StoreFields\n        }\n      }\n    "): typeof import('./graphql').CreateStoreDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation UpdateStore($id: ID!, $input: UpdateStoreInput!) {\n        updateStore(id: $id, input: $input) {\n          ...StoreFields\n        }\n      }\n    "): typeof import('./graphql').UpdateStoreDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation FlagStore($recordId: ID!, $reason: String) {\n        flagRecord(recordType: STORE, recordId: $recordId, reason: $reason) {\n          flagCount\n          hidden\n        }\n      }\n    "): typeof import('./graphql').FlagStoreDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query GeocodeAddress($street: String, $city: String!, $postalCode: String) {\n        geocodeAddress(street: $street, city: $city, postalCode: $postalCode) {\n          attribution\n          candidates {\n            lat\n            lon\n            displayName\n            osmRef\n          }\n        }\n      }\n    "): typeof import('./graphql').GeocodeAddressDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query ReverseGeocode($lat: Float!, $lon: Float!) {\n        reverseGeocode(lat: $lat, lon: $lon) {\n          street\n          city\n          postalCode\n          country\n          osmRef\n          attribution\n        }\n      }\n    "): typeof import('./graphql').ReverseGeocodeDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Chains($query: String, $country: String, $first: Int) {\n        chains(query: $query, country: $country, first: $first) {\n          id\n          name\n          chainType\n        }\n      }\n    "): typeof import('./graphql').ChainsDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query CompanyByIco($ico: String!) {\n        companyByIco(ico: $ico) {\n          ico\n          name\n          street\n          city\n          postalCode\n        }\n      }\n    "): typeof import('./graphql').CompanyByIcoDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      query Me {\n        me {\n          publicHandle\n          displayName\n          createdAt\n          trusted\n          moderator\n          locale\n          country\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    "): typeof import('./graphql').MeDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation SetLocale($locale: String!, $country: String) {\n        setLocale(locale: $locale, country: $country) {\n          locale\n          country\n        }\n      }\n    "): typeof import('./graphql').SetLocaleDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation UpdateProfile($input: UpdateProfileInput!) {\n        updateProfile(input: $input) {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    "): typeof import('./graphql').UpdateProfileDocument;
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n      mutation DeleteAvatar {\n        deleteAvatar {\n          publicHandle\n          displayName\n          profile {\n            ...ProfileFields\n          }\n        }\n      }\n    "): typeof import('./graphql').DeleteAvatarDocument;


export function graphql(source: string) {
  return (documents as any)[source] ?? {};
}
