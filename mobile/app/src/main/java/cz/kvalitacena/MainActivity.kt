package cz.kvalitacena

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cz.kvalitacena.ui.about.AboutScreen
import cz.kvalitacena.ui.about.ChangelogScreen
import cz.kvalitacena.ui.account.AccountScreen
import cz.kvalitacena.ui.common.NavigationResults
import cz.kvalitacena.ui.common.UpdateRequiredScreen
import cz.kvalitacena.ui.common.UpdateRequiredState
import cz.kvalitacena.ui.contributions.MyContributionsScreen
import cz.kvalitacena.ui.detail.ProductDetailScreen
import cz.kvalitacena.ui.feedback.FeedbackScreen
import cz.kvalitacena.ui.legal.PrivacyScreen
import cz.kvalitacena.ui.legal.TermsScreen
import cz.kvalitacena.receipt.ReceiptNotifications
import cz.kvalitacena.receipt.isAllowedNotificationRoute
import cz.kvalitacena.ui.navigation.ARG_BARCODE
import cz.kvalitacena.ui.navigation.ARG_NAME
import cz.kvalitacena.ui.navigation.ARG_RECEIPT_ID
import cz.kvalitacena.ui.navigation.ARG_RETAKE
import cz.kvalitacena.ui.navigation.ROUTE_RECEIPTS
import cz.kvalitacena.ui.navigation.ROUTE_RECEIPT_CAPTURE
import cz.kvalitacena.ui.navigation.ROUTE_RECEIPT_DETAIL
import cz.kvalitacena.ui.navigation.ROUTE_RECEIPT_REVIEW
import cz.kvalitacena.ui.navigation.ROUTE_SCAN_CODE
import cz.kvalitacena.ui.navigation.receiptCaptureRoute
import cz.kvalitacena.ui.navigation.receiptDetailRoute
import cz.kvalitacena.ui.receipt.capture.ReceiptCaptureScreen
import cz.kvalitacena.ui.receipt.detail.ReceiptDetailScreen
import cz.kvalitacena.ui.receipt.list.ReceiptListScreen
import cz.kvalitacena.ui.receipt.review.ReceiptReviewScreen
import cz.kvalitacena.ui.navigation.ARG_FEEDBACK_SOURCE
import cz.kvalitacena.ui.navigation.ARG_PRODUCT_ID
import cz.kvalitacena.ui.navigation.ARG_STORE_ID
import cz.kvalitacena.ui.navigation.ARG_WRITE_PRICE
import cz.kvalitacena.ui.navigation.ROUTE_ABOUT
import cz.kvalitacena.ui.navigation.ROUTE_CHANGELOG
import cz.kvalitacena.ui.navigation.ROUTE_FEEDBACK
import cz.kvalitacena.ui.navigation.ROUTE_PRICE_ENTRY
import cz.kvalitacena.ui.navigation.ROUTE_PRODUCT_DETAIL
import cz.kvalitacena.ui.navigation.ROUTE_PRODUCT_FORM
import cz.kvalitacena.ui.navigation.ROUTE_PRIVACY
import cz.kvalitacena.ui.navigation.ROUTE_MY_CONTRIBUTIONS
import cz.kvalitacena.ui.navigation.ROUTE_PROFILE
import cz.kvalitacena.ui.navigation.ROUTE_TERMS
import cz.kvalitacena.ui.navigation.ROUTE_STORE_DETAIL
import cz.kvalitacena.ui.navigation.ROUTE_STORE_FORM
import cz.kvalitacena.ui.navigation.TopLevelDestination
import cz.kvalitacena.ui.navigation.feedbackRoute
import cz.kvalitacena.ui.navigation.isTopLevelRoute
import cz.kvalitacena.ui.navigation.LocalNavigationExitGuard
import cz.kvalitacena.ui.navigation.NavigationExitGuardDialog
import cz.kvalitacena.ui.navigation.NavigationExitGuardState
import cz.kvalitacena.ui.navigation.nestedRouteTitle
import cz.kvalitacena.ui.navigation.priceEntryRouteByBarcode
import cz.kvalitacena.ui.navigation.priceEntryRouteByProductId
import cz.kvalitacena.ui.navigation.productDetailRoute
import cz.kvalitacena.ui.navigation.productFormRoute
import cz.kvalitacena.ui.navigation.productFormRouteForEdit
import cz.kvalitacena.ui.navigation.storeDetailRoute
import cz.kvalitacena.ui.navigation.storeFormRouteForCreate
import cz.kvalitacena.ui.navigation.storeFormRouteForEdit
import cz.kvalitacena.ui.price.PriceEntryScreen
import cz.kvalitacena.ui.price.PriceEntryTarget
import cz.kvalitacena.ui.product.ProductFormScreen
import cz.kvalitacena.ui.profile.ProfileScreen
import cz.kvalitacena.ui.scan.ScanScreen
import cz.kvalitacena.ui.search.SearchScreen
import cz.kvalitacena.ui.settings.SettingsScreen
import cz.kvalitacena.ui.store.StoreDetailScreen
import cz.kvalitacena.ui.store.StoreFormScreen
import cz.kvalitacena.ui.theme.KvalitaACenaTheme
import kotlinx.coroutines.launch

// AppCompatActivity, ne ComponentActivity — AppCompatDelegate.setApplicationLocales()
// (LocaleController.kt) potřebuje aktivní AppCompatDelegate, jinak je no-op na všech API
// úrovních (appka po přepnutí jazyka v Nastavení zůstávala ve starém jazyce).
class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    DebugRouteIntent.accept(intent)
    NotificationRouteIntent.accept(intent)

    // Zkusí obnovit přihlášení z uloženého refresh tokenu — stejný princip jako
    // frontend/src/app/func/auth-initializer.ts. Anonymní chod appky (T0) tím není podmíněný.
    // Obrazovky na to nečekají: stav přihlášení je znát z uloženého refresh tokenu hned
    // (`isLoggedIn`) a každý dotaz si platný access token obstará sám (`validAccessToken`).
    lifecycleScope.launch { AppContainer.authRepository.restoreSession() }

    setContent {
      KvalitaACenaTheme {
        AppScaffold()
      }
    }
  }

  // `ui.py open` posílá intent s --activity-single-top, takže běžící appka ho dostane sem
  // (jinak by se aktivita se standardním launchMode zakládala znovu a přišla o stav).
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    DebugRouteIntent.accept(intent)
    NotificationRouteIntent.accept(intent)
  }
}

/**
 * Trasa z notifikace o zpracované účtence (receipt/ReceiptNotifications.kt). Na rozdíl od
 * [DebugRouteIntent] platí i ve vydané appce — proto jen seznam nebo detail účtenky
 * ([isAllowedNotificationRoute]): MainActivity je exportovaná a extra může poslat kdokoli.
 */
private object NotificationRouteIntent {
  var pending by mutableStateOf<String?>(null)
    private set

  fun accept(intent: Intent?) {
    val route = intent?.getStringExtra(ReceiptNotifications.EXTRA_ROUTE) ?: return
    intent.removeExtra(ReceiptNotifications.EXTRA_ROUTE)
    if (isAllowedNotificationRoute(route)) pending = route
  }

  fun consume(): String? = pending?.also { pending = null }
}

/**
 * Skok na obrazovku poslaný z ADB (`tools/mobile/ui.py open <route>`, tedy
 * `am start -n cz.kvalitacena/.MainActivity --activity-single-top -e route "product/<id>"`) —
 * díky němu jde appka ladit textově, bez screenshotů (`mobile/CLAUDE.md`, "Ladění
 * v emulátoru"); jinak by se ke každé vnitřní obrazovce muselo proklikat.
 *
 * Záměrně NE `navDeepLink` u každého `composable` s `VIEW` intent-filtrem: tohle je jeden blok
 * navíc místo deseti, funguje pro libovolnou trasu z `AppDestinations.kt` včetně query
 * parametrů, a hlavně ho `BuildConfig.DEBUG` v release buildu vypne úplně — MainActivity je
 * sice exportovaná (LAUNCHER), ale vydaná appka si extra `route` ani nepřečte a manifest se
 * kvůli ladění nemění.
 */
private object DebugRouteIntent {
  private const val EXTRA = "route"

  var pending by mutableStateOf<String?>(null)
    private set

  /** Přečte trasu z intentu a z intentu ji SMAŽE, ať se skok neopakuje při obnově aktivity. */
  fun accept(intent: Intent?) {
    if (!BuildConfig.DEBUG) return
    val route = intent?.getStringExtra(EXTRA)?.takeIf { it.isNotBlank() } ?: return
    intent.removeExtra(EXTRA)
    pending = route
  }

  fun consume(): String? = pending?.also { pending = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold() {
  // Backend odmítá úplně všechny požadavky staré appce (ClientVersionFilter), takže se
  // nahrazuje celý Scaffold, ne jen aktuální obrazovka.
  if (UpdateRequiredState.required) {
    UpdateRequiredScreen()
    return
  }

  val navController = rememberNavController()
  val currentEntry by navController.currentBackStackEntryAsState()
  val currentRoute = currentEntry?.destination?.route
  val isTopLevel = isTopLevelRoute(currentRoute)
  val exitGuard = remember { NavigationExitGuardState() }

  LaunchedEffect(currentEntry?.id) { exitGuard.clear() }

  // Konstantní podmínka záměrně: takhle R8 vyhodí z release buildu i tenhle blok, ne jen
  // samotný DebugRouteIntent (ověřeno na classes.dex vydaného APK).
  if (BuildConfig.DEBUG) {
    val debugRoute = DebugRouteIntent.pending
    LaunchedEffect(debugRoute) {
      val route = DebugRouteIntent.consume() ?: return@LaunchedEffect
      // Překlep v trase nesmí shodit appku — navigate() na neznámou trasu hází výjimku.
      runCatching { navController.navigate(route) }
        .onFailure { Log.w("DebugRouteIntent", "Unknown route: $route", it) }
    }
  }

  val notificationRoute = NotificationRouteIntent.pending
  LaunchedEffect(notificationRoute) {
    val route = NotificationRouteIntent.consume() ?: return@LaunchedEffect
    // Seznam jako podklad pod detailem — Zpět z detailu vede na ostatní účtenky, ne ven.
    navController.navigate(ROUTE_RECEIPTS) { launchSingleTop = true }
    if (route != ROUTE_RECEIPTS) navController.navigate(route)
  }

  fun navigateBack() {
    if (!navController.navigateUp()) {
      navController.navigate(TopLevelDestination.SEARCH.route) {
        popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
        launchSingleTop = true
      }
    }
  }

  CompositionLocalProvider(LocalNavigationExitGuard provides exitGuard) {
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(if (isTopLevel) R.string.app_name else nestedRouteTitle(currentRoute)))
            // Verze appky jen na záložce Nastavení — appbar je sdílený přes celou appku,
            // jinde by šlo o neužitečný šum (uživatel chtěl vidět, jestli appka odpovídá
            // vydané verzi backendu/webu, ne verzi na každé obrazovce).
            if (currentRoute == TopLevelDestination.SETTINGS.route) {
              Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          if (!isTopLevel) {
            IconButton(onClick = { exitGuard.requestNavigation(::navigateBack) }) {
              Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.common_back),
              )
            }
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
      )
    },
    bottomBar = {
      if (isTopLevel) AppBottomBar(navController, exitGuard)
    },
  ) { padding ->
    NavHost(
      navController = navController,
      // Hledání je "úvodní obrazovka" (zadání), i když sken je v baru první — systémové Zpět
      // z libovolné záložky tak vede na hledání a odtud ven z appky, standardní chování.
      startDestination = TopLevelDestination.SEARCH.route,
      modifier = Modifier.padding(padding),
    ) {
      composable(TopLevelDestination.SCAN.route) {
        ScanScreen(
          onBarcodeDetected = { code -> navController.navigate(priceEntryRouteByBarcode(code)) },
          onScanReceipt = { navController.navigate(receiptCaptureRoute()) },
        )
      }
      composable(TopLevelDestination.SEARCH.route) {
        SearchScreen(
          onProductClick = { productId -> navController.navigate(productDetailRoute(productId)) },
          onAddProduct = { navController.navigate(productFormRoute(writePrice = true)) },
        )
      }
      composable(TopLevelDestination.SETTINGS.route) {
        SettingsScreen(
          onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
          onOpenFeedback = { navController.navigate(feedbackRoute("settings")) },
          onOpenTerms = { navController.navigate(ROUTE_TERMS) },
          onOpenPrivacy = { navController.navigate(ROUTE_PRIVACY) },
        )
      }
      composable(TopLevelDestination.ACCOUNT.route) {
        AccountScreen(
          onEditProfile = { navController.navigate(ROUTE_PROFILE) },
          onOpenMyContributions = { navController.navigate(ROUTE_MY_CONTRIBUTIONS) },
          onOpenTerms = { navController.navigate(ROUTE_TERMS) },
          onOpenPrivacy = { navController.navigate(ROUTE_PRIVACY) },
          onOpenReceipts = { navController.navigate(ROUTE_RECEIPTS) },
        )
      }
      composable(
        ROUTE_PRODUCT_DETAIL,
        arguments = listOf(navArgument(ARG_PRODUCT_ID) { type = NavType.StringType }),
      ) { backStackEntry ->
        val productId = backStackEntry.arguments?.getString(ARG_PRODUCT_ID).orEmpty()
        ProductDetailScreen(
          productId = productId,
          onWriteObservation = { navController.navigate(priceEntryRouteByProductId(productId)) },
          onNavigateToAccount = {
            navController.navigate(TopLevelDestination.ACCOUNT.route) { launchSingleTop = true }
          },
          onStoreClick = { storeId -> navController.navigate(storeDetailRoute(storeId)) },
          onEditProduct = { id -> navController.navigate(productFormRouteForEdit(id)) },
        )
      }
      composable(
        ROUTE_PRICE_ENTRY,
        arguments = listOf(
          navArgument(ARG_BARCODE) { type = NavType.StringType; nullable = true; defaultValue = null },
          navArgument(ARG_PRODUCT_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
      ) { backStackEntry ->
        val barcode = backStackEntry.arguments?.getString(ARG_BARCODE)
        val productId = backStackEntry.arguments?.getString(ARG_PRODUCT_ID)
        val target = when {
          !productId.isNullOrBlank() -> PriceEntryTarget.ById(productId)
          !barcode.isNullOrBlank() -> PriceEntryTarget.ByBarcode(barcode)
          else -> null
        }
        if (target != null) {
          PriceEntryScreen(
            target = target,
            onDone = { navController.popBackStack() },
            onAddStore = { navController.navigate(storeFormRouteForCreate()) },
            onAddProduct = { newBarcode -> navController.navigate(productFormRoute(newBarcode)) },
            onSearchPrices = { query ->
              NavigationResults.searchQuery = query
              // Stejný vzor jako spodní lišta (AppBottomBar níž) — čistý zásobník na Hledání.
              navController.navigate(TopLevelDestination.SEARCH.route) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
              }
            },
          )
        }
      }
      composable(
        ROUTE_STORE_FORM,
        arguments = listOf(navArgument(ARG_STORE_ID) { type = NavType.StringType; nullable = true; defaultValue = null }),
      ) { backStackEntry ->
        val storeId = backStackEntry.arguments?.getString(ARG_STORE_ID)
        StoreFormScreen(storeId = storeId, onDone = { navController.popBackStack() })
      }
      composable(
        ROUTE_STORE_DETAIL,
        arguments = listOf(navArgument(ARG_STORE_ID) { type = NavType.StringType }),
      ) { backStackEntry ->
        val storeId = backStackEntry.arguments?.getString(ARG_STORE_ID).orEmpty()
        StoreDetailScreen(
          storeId = storeId,
          onEditStore = { id -> navController.navigate(storeFormRouteForEdit(id)) },
        )
      }
      composable(
        ROUTE_PRODUCT_FORM,
        arguments = listOf(
          navArgument(ARG_BARCODE) { type = NavType.StringType; nullable = true; defaultValue = null },
          navArgument(ARG_WRITE_PRICE) { type = NavType.BoolType; defaultValue = false },
          navArgument(ARG_PRODUCT_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
          navArgument(ARG_NAME) { type = NavType.StringType; nullable = true; defaultValue = null },
          navArgument(ARG_STORE_ID) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
      ) { backStackEntry ->
        val barcode = backStackEntry.arguments?.getString(ARG_BARCODE)
        val writePrice = backStackEntry.arguments?.getBoolean(ARG_WRITE_PRICE) ?: false
        val productId = backStackEntry.arguments?.getString(ARG_PRODUCT_ID)
        ProductFormScreen(
          barcode = barcode,
          productId = productId,
          initialName = backStackEntry.arguments?.getString(ARG_NAME),
          initialStoreId = backStackEntry.arguments?.getString(ARG_STORE_ID),
          onScanCode = { navController.navigate(ROUTE_SCAN_CODE) },
          onDone = { navController.popBackStack() },
          onAddStore = { navController.navigate(storeFormRouteForCreate()) },
          onCreated = if (writePrice) {
            { productId ->
              // Rovnou na zápis ceny nově založeného zboží (bez EANu, ze SearchScreen) — ať
              // uživatel po založení nemusí nic hledat znovu. Nahradit product_form v zásobníku,
              // ne na něj přidávat další patro (Zpět z ceny by jinak vedlo na prázdný formulář).
              navController.navigate(priceEntryRouteByProductId(productId)) {
                popUpTo(ROUTE_PRODUCT_FORM) { inclusive = true }
              }
            }
          } else {
            { navController.popBackStack() }
          },
        )
      }
      composable(ROUTE_SCAN_CODE) {
        ScanScreen(
          onBarcodeDetected = { code ->
            NavigationResults.scannedCode = code
            navController.popBackStack()
          },
        )
      }
      composable(ROUTE_RECEIPTS) {
        ReceiptListScreen(
          onOpenReceipt = { id -> navController.navigate(receiptDetailRoute(id)) },
          onCapture = { navController.navigate(receiptCaptureRoute()) },
          onOpenAccount = { navController.navigate(TopLevelDestination.ACCOUNT.route) { launchSingleTop = true } },
          onOpenPrivacy = { navController.navigate(ROUTE_PRIVACY) },
        )
      }
      composable(
        ROUTE_RECEIPT_DETAIL,
        arguments = listOf(navArgument(ARG_RECEIPT_ID) { type = NavType.StringType }),
      ) { backStackEntry ->
        val receiptId = backStackEntry.arguments?.getString(ARG_RECEIPT_ID).orEmpty()
        ReceiptDetailScreen(
          receiptId = receiptId,
          onCreateProduct = { name, storeId -> navController.navigate(productFormRoute(name = name, storeId = storeId)) },
          onAddStore = { navController.navigate(storeFormRouteForCreate()) },
          onProductClick = { id -> navController.navigate(productDetailRoute(id)) },
          onRescan = { navController.navigate(receiptCaptureRoute()) },
          onDeleted = { navController.popBackStack() },
          onOpenAccount = { navController.navigate(TopLevelDestination.ACCOUNT.route) { launchSingleTop = true } },
        )
      }
      composable(
        ROUTE_RECEIPT_CAPTURE,
        arguments = listOf(navArgument(ARG_RETAKE) { type = NavType.IntType; defaultValue = -1 }),
      ) { backStackEntry ->
        val retake = backStackEntry.arguments?.getInt(ARG_RETAKE)?.takeIf { it >= 0 }
        ReceiptCaptureScreen(
          retakeIndex = retake,
          onDone = {
            // Z kontroly („přidat další díl", „nafotit znovu") se vrací zpátky do ní, jinak
            // snímání kontrolou nahradí — Zpět z kontroly pak nevede do kamery.
            if (retake != null || navController.previousBackStackEntry?.destination?.route == ROUTE_RECEIPT_REVIEW) {
              navController.popBackStack()
            } else {
              navController.navigate(ROUTE_RECEIPT_REVIEW) {
                popUpTo(ROUTE_RECEIPT_CAPTURE) { inclusive = true }
              }
            }
          },
          onOpenAccount = { navController.navigate(TopLevelDestination.ACCOUNT.route) { launchSingleTop = true } },
        )
      }
      composable(ROUTE_RECEIPT_REVIEW) {
        ReceiptReviewScreen(
          onRetake = { index -> navController.navigate(receiptCaptureRoute(index)) },
          onAddMore = { navController.navigate(receiptCaptureRoute()) },
          onSubmitted = {
            navController.navigate(ROUTE_RECEIPTS) {
              popUpTo(ROUTE_RECEIPT_REVIEW) { inclusive = true }
              launchSingleTop = true
            }
          },
          onOpenPrivacy = { navController.navigate(ROUTE_PRIVACY) },
          onOpenAccount = { navController.navigate(TopLevelDestination.ACCOUNT.route) { launchSingleTop = true } },
        )
      }
      composable(ROUTE_PROFILE) {
        ProfileScreen(onDone = { navController.popBackStack() })
      }
      composable(ROUTE_MY_CONTRIBUTIONS) {
        MyContributionsScreen(
          onProductClick = { productId -> navController.navigate(productDetailRoute(productId)) },
          onStoreClick = { storeId -> navController.navigate(storeDetailRoute(storeId)) },
        )
      }
      composable(ROUTE_TERMS) {
        TermsScreen(onDone = { navController.popBackStack() })
      }
      composable(ROUTE_PRIVACY) {
        PrivacyScreen(onDone = { navController.popBackStack() })
      }
      composable(ROUTE_ABOUT) {
        AboutScreen(
          onDone = { navController.popBackStack() },
          onOpenFeedback = { navController.navigate(feedbackRoute("about")) },
          onOpenTerms = { navController.navigate(ROUTE_TERMS) },
          onOpenPrivacy = { navController.navigate(ROUTE_PRIVACY) },
          onOpenChangelog = { navController.navigate(ROUTE_CHANGELOG) },
        )
      }
      composable(ROUTE_CHANGELOG) {
        ChangelogScreen(onDone = { navController.popBackStack() })
      }
      composable(
        ROUTE_FEEDBACK,
        arguments = listOf(navArgument(ARG_FEEDBACK_SOURCE) { type = NavType.StringType; nullable = true; defaultValue = null }),
      ) { backStackEntry ->
        val source = backStackEntry.arguments?.getString(ARG_FEEDBACK_SOURCE) ?: "unknown"
        FeedbackScreen(source = source, onDone = { navController.popBackStack() })
      }
    }
  }
  // Registrovat AŽ PO NavHostu — OnBackPressedDispatcher volá naposledy přidaný callback jako
  // první, a NavHost si dovnitř svého composable stromu (výš) registruje vlastní
  // PredictiveBackHandler. Kdyby byl tenhle BackHandler zaregistrovaný dřív (např. před
  // Scaffold), systémové Zpět by ho obcházelo a šlo by rovnou přes NavHost bez potvrzení
  // rozepsaného formuláře.
  BackHandler(enabled = !isTopLevel || exitGuard.dirty) {
    exitGuard.requestNavigation(::navigateBack)
  }
  NavigationExitGuardDialog(exitGuard)
  }
}

/**
 * Čtyři čtvercové ikony podle zadání. Přepínání zachovává stav jednotlivých záložek
 * (`saveState`/`restoreState`) — standardní vzor pro bottom navigation.
 */
@Composable
private fun AppBottomBar(navController: NavHostController, exitGuard: NavigationExitGuardState) {
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route

  NavigationBar {
    TopLevelDestination.entries.forEach { destination ->
      NavigationBarItem(
        selected = currentRoute == destination.route,
        onClick = { exitGuard.requestNavigation {
          // Vždy čistý zásobník — jen jedna položka menu. Standardní vzor se saveState/
          // restoreState (obnova stavu při návratu na záložku) se tu ukázal nespolehlivý:
          // návrat na "search" (startDestination) se za určitých stavů zásobníku vůbec
          // neprovedl, appka zůstala viset na předchozí obrazovce. `inclusive = true` smaže
          // celý zásobník včetně startDestination a založí ho znovu — o kus dražší (ztrácí
          // se např. rozepsaný dotaz v hledání), ale spolehlivé, a to tu má přednost.
          navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
          }
        } },
        icon = {
          Icon(
            painter = painterResource(destination.iconRes),
            contentDescription = stringResource(destination.labelRes),
          )
        },
        label = { Text(stringResource(destination.labelRes)) },
      )
    }
  }
}
