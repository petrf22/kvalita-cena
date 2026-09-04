package cz.kvalitacena

import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
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
import cz.kvalitacena.ui.navigation.ARG_BARCODE
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

    // Zkusí obnovit přihlášení z uloženého refresh tokenu — stejný princip jako
    // frontend/src/app/func/auth-initializer.ts. Anonymní chod appky (T0) tím není podmíněný.
    lifecycleScope.launch { AppContainer.authRepository.refresh() }

    setContent {
      KvalitaACenaTheme {
        AppScaffold()
      }
    }
  }
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
        ),
      ) { backStackEntry ->
        val barcode = backStackEntry.arguments?.getString(ARG_BARCODE)
        val writePrice = backStackEntry.arguments?.getBoolean(ARG_WRITE_PRICE) ?: false
        val productId = backStackEntry.arguments?.getString(ARG_PRODUCT_ID)
        ProductFormScreen(
          barcode = barcode,
          productId = productId,
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
