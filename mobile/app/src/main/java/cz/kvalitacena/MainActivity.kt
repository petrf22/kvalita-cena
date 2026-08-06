package cz.kvalitacena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import cz.kvalitacena.ui.account.AccountScreen
import cz.kvalitacena.ui.detail.ProductDetailScreen
import cz.kvalitacena.ui.navigation.ARG_BARCODE
import cz.kvalitacena.ui.navigation.ARG_PRODUCT_ID
import cz.kvalitacena.ui.navigation.ROUTE_PRICE_ENTRY
import cz.kvalitacena.ui.navigation.ROUTE_PRODUCT_DETAIL
import cz.kvalitacena.ui.navigation.TopLevelDestination
import cz.kvalitacena.ui.navigation.priceEntryRouteByBarcode
import cz.kvalitacena.ui.navigation.priceEntryRouteByProductId
import cz.kvalitacena.ui.navigation.productDetailRoute
import cz.kvalitacena.ui.price.PriceEntryScreen
import cz.kvalitacena.ui.price.PriceEntryTarget
import cz.kvalitacena.ui.scan.ScanScreen
import cz.kvalitacena.ui.search.SearchScreen
import cz.kvalitacena.ui.settings.SettingsScreen
import cz.kvalitacena.ui.theme.KvalitaACenaTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
  val navController = rememberNavController()

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
      )
    },
    bottomBar = { AppBottomBar(navController) },
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
        )
      }
      composable(TopLevelDestination.SETTINGS.route) {
        SettingsScreen()
      }
      composable(TopLevelDestination.ACCOUNT.route) {
        AccountScreen()
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
          PriceEntryScreen(target = target, onDone = { navController.popBackStack() })
        }
      }
    }
  }
}

/**
 * Čtyři čtvercové ikony podle zadání. Přepínání zachovává stav jednotlivých záložek
 * (`saveState`/`restoreState`) — standardní vzor pro bottom navigation.
 */
@Composable
private fun AppBottomBar(navController: NavHostController) {
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route

  NavigationBar {
    TopLevelDestination.entries.forEach { destination ->
      NavigationBarItem(
        selected = currentRoute == destination.route,
        onClick = {
          navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
          }
        },
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
