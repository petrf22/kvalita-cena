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
