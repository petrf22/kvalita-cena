package cz.kvalitacena.ui.navigation

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

/** Založení zboží — s EANem i bez (bezkódová druhová položka, docs/reputace.md). */
const val ROUTE_PRODUCT_FORM = "product_form?$ARG_BARCODE={$ARG_BARCODE}"

fun productFormRoute(barcode: String? = null) =
  if (barcode.isNullOrBlank()) "product_form" else "product_form?barcode=$barcode"

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
