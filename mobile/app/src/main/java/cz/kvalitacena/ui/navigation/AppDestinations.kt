package cz.kvalitacena.ui.navigation

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import cz.kvalitacena.R

/**
 * Spodní menu ze čtyř čtvercových ikon (viz zadání) — pořadí v baru odpovídá zadání (sken
 * první), ale `startDestination` v MainActivity je SEARCH, protože hledání je "úvodní
 * obrazovka".
 */
enum class TopLevelDestination(
  val route: String,
  @StringRes val labelRes: Int,
  @DrawableRes val iconRes: Int,
) {
  SCAN("scan", R.string.tab_scan, R.drawable.ic_tab_scan),
  SEARCH("search", R.string.tab_search, R.drawable.ic_tab_search),
  SETTINGS("settings", R.string.tab_settings, R.drawable.ic_tab_settings),
  ACCOUNT("account", R.string.tab_account, R.drawable.ic_tab_account),
}

const val ARG_PRODUCT_ID = "productId"
const val ROUTE_PRODUCT_DETAIL = "product/{$ARG_PRODUCT_ID}"

fun productDetailRoute(productId: String) = "product/$productId"

const val ARG_BARCODE = "barcode"
const val ROUTE_PRICE_ENTRY = "price_entry?barcode={$ARG_BARCODE}&$ARG_PRODUCT_ID={$ARG_PRODUCT_ID}"

fun priceEntryRouteByBarcode(barcode: String) = "price_entry?barcode=$barcode"
fun priceEntryRouteByProductId(productId: String) = "price_entry?$ARG_PRODUCT_ID=$productId"

const val ARG_STORE_ID = "storeId"
const val ROUTE_STORE_DETAIL = "store/{$ARG_STORE_ID}"

fun storeDetailRoute(storeId: String) = "store/$storeId"

/**
 * Založení obchodu — dostupné odkudkoli, kde se vybírá provozovna (StorePicker "+ Přidat
 * nový"). Volitelný `storeId` v query přepne obrazovku do režimu editace (StoreDetailScreen
 * "Upravit").
 */
const val ROUTE_STORE_FORM = "store_form?$ARG_STORE_ID={$ARG_STORE_ID}"

fun storeFormRouteForCreate() = "store_form"
fun storeFormRouteForEdit(storeId: String) = "store_form?$ARG_STORE_ID=$storeId"

/** True jen pro čtyři kořenové záložky, na kterých má být vidět spodní navigace. */
fun isTopLevelRoute(route: String?): Boolean = TopLevelDestination.entries.any { it.route == route }

/** Lokalizovaný titul vnořených obrazovek pro společný app bar v MainActivity. */
@StringRes
fun nestedRouteTitle(route: String?): Int = when (route) {
  ROUTE_PRODUCT_DETAIL -> R.string.navigation_product_detail
  ROUTE_PRICE_ENTRY -> R.string.price_entry_write_price
  ROUTE_STORE_DETAIL -> R.string.navigation_store_detail
  ROUTE_STORE_FORM -> R.string.navigation_store_form
  ROUTE_PRODUCT_FORM -> R.string.product_form_title
  ROUTE_PROFILE -> R.string.profile_title
  ROUTE_MY_CONTRIBUTIONS -> R.string.my_contributions_title
  ROUTE_TERMS -> R.string.terms_title
  ROUTE_PRIVACY -> R.string.privacy_title
  ROUTE_ABOUT -> R.string.about_title
  ROUTE_CHANGELOG -> R.string.changelog_title
  ROUTE_FEEDBACK -> R.string.feedback_title
  ROUTE_RECEIPTS -> R.string.receipts_title
  ROUTE_RECEIPT_DETAIL -> R.string.receipt_detail_title
  ROUTE_RECEIPT_CAPTURE -> R.string.receipt_capture_title
  ROUTE_RECEIPT_REVIEW -> R.string.receipt_review_title
  ROUTE_SCAN_CODE -> R.string.scan_code_title
  else -> R.string.app_name
}

/**
 * Založení zboží — s EANem i bez (bezkódová druhová položka, docs/reputace.md). `writePrice`
 * odlišuje vstup ze SearchScreen (bez EANu, appka po založení chce rovnou zápis ceny) od vstupu
 * ze skenu neznámého EANu (PriceEntryScreen "kód nenalezen" už na obrazovce zápisu ceny je,
 * po založení se prostě vrátí zpátky) — jedna sdílená obrazovka, dvě různá "co dál" po úspěchu.
 * Volitelný `productId` v query přepne obrazovku do režimu editace (ProductDetailScreen
 * "Upravit"), stejný vzor jako ROUTE_STORE_FORM.
 */
const val ARG_WRITE_PRICE = "writePrice"

/**
 * Předvyplněný název a obchod — „Založit nové zboží" z řádku účtenky (ReceiptDetailScreen):
 * název z textu účtenky (`labelRaw`), obchod z hlavičky účtenky.
 */
const val ARG_NAME = "name"
const val ROUTE_PRODUCT_FORM =
  "product_form?$ARG_BARCODE={$ARG_BARCODE}&$ARG_WRITE_PRICE={$ARG_WRITE_PRICE}&$ARG_PRODUCT_ID={$ARG_PRODUCT_ID}" +
    "&$ARG_NAME={$ARG_NAME}&$ARG_STORE_ID={$ARG_STORE_ID}"

fun productFormRoute(
  barcode: String? = null,
  writePrice: Boolean = false,
  name: String? = null,
  storeId: String? = null,
): String {
  val params = buildList {
    if (!barcode.isNullOrBlank()) add("$ARG_BARCODE=$barcode")
    if (writePrice) add("$ARG_WRITE_PRICE=true")
    if (!name.isNullOrBlank()) add("$ARG_NAME=${Uri.encode(name)}")
    if (!storeId.isNullOrBlank()) add("$ARG_STORE_ID=$storeId")
  }
  return if (params.isEmpty()) "product_form" else "product_form?" + params.joinToString("&")
}

fun productFormRouteForEdit(productId: String) = "product_form?$ARG_PRODUCT_ID=$productId"

/** Editace profilu (docs/soukromi.md, "Profil uživatele a viditelnost") — ze záložky Účet. */
const val ROUTE_PROFILE = "profile"

/**
 * "Moje příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty") — vlastní
 * založené zboží/obchody, vlastní zapsané ceny a vlastní úpravy cizích záznamů, se stavem
 * zveřejnění. Ze záložky Účet, stejně jako ROUTE_PROFILE.
 */
const val ROUTE_MY_CONTRIBUTIONS = "my_contributions"

/** Podmínky užití a Zásady ochrany osobních údajů — z Nastavení i ze souhlasu na přihlášení. */
const val ROUTE_TERMS = "terms"
const val ROUTE_PRIVACY = "privacy"

/** "O aplikaci" — z Nastavení, viz [cz.kvalitacena.ui.about.AboutScreen]. */
const val ROUTE_ABOUT = "about"

/**
 * "Novinky" — seznam změn appky, z "O aplikaci". Data čte z `assets/changelog.json`
 * (generováno `tools/version/sync.mjs` z kořenového `CHANGELOG.md`, needituj ručně), viz
 * [cz.kvalitacena.ui.about.ChangelogScreen]. Webový protějšek:
 * `frontend/src/app/features/changelog/changelog-page.ts`.
 */
const val ROUTE_CHANGELOG = "changelog"

/**
 * Zpětná vazba (core.feedback, docs/nasazeni.md "Než pozvat první lidi") — z Nastavení i
 * z "O aplikaci", funguje i bez přihlášení. `source` v query je orientační popis obrazovky,
 * ze které se sem vstoupilo (core.feedback.page_ref), viz [cz.kvalitacena.ui.feedback.FeedbackScreen].
 */
const val ARG_FEEDBACK_SOURCE = "source"
const val ROUTE_FEEDBACK = "feedback?$ARG_FEEDBACK_SOURCE={$ARG_FEEDBACK_SOURCE}"

fun feedbackRoute(source: String) = "feedback?$ARG_FEEDBACK_SOURCE=$source"

/**
 * Účtenky (docs/soukromi.md, „Účtenky") — seznam ze záložky Účet, detail s párováním řádků,
 * snímání kamerou (ze záložky Sken) a kontrola před odesláním (masky, souhlas). Seznam i detail
 * otevírá i notifikace o zpracované účtence (receipt/ReceiptNotifications.kt).
 */
const val ROUTE_RECEIPTS = "receipts"

const val ARG_RECEIPT_ID = "receiptId"
const val ROUTE_RECEIPT_DETAIL = "receipt/{$ARG_RECEIPT_ID}"

fun receiptDetailRoute(receiptId: String) = "receipt/$receiptId"

/** `retake` = index dílu, který se má nafotit znovu (z kontroly); bez něj běžné snímání. */
const val ARG_RETAKE = "retake"
const val ROUTE_RECEIPT_CAPTURE = "receipt_capture?$ARG_RETAKE={$ARG_RETAKE}"

fun receiptCaptureRoute(retakeIndex: Int? = null) =
  if (retakeIndex == null) "receipt_capture" else "receipt_capture?$ARG_RETAKE=$retakeIndex"

const val ROUTE_RECEIPT_REVIEW = "receipt_review"

/**
 * Sken čárového kódu v režimu „vrátit kód" — tlačítko u prázdného pole EAN ve formuláři zboží.
 * Stejná [cz.kvalitacena.ui.scan.ScanScreen], jen kód nejde na zápis ceny, ale zpátky do
 * formuláře (NavigationResults.scannedCode).
 */
const val ROUTE_SCAN_CODE = "scan_code"
