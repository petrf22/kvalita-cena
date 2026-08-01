package cz.kvalitacena

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cz.kvalitacena.ui.login.LoginScreen
import cz.kvalitacena.ui.price.PriceEntryScreen
import cz.kvalitacena.ui.scan.ScanScreen
import cz.kvalitacena.ui.theme.KvalitaACenaTheme
import kotlinx.coroutines.launch

private const val ROUTE_SCAN = "scan"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_PRICE_ENTRY = "price_entry/{barcode}"

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
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()
  val scope = rememberCoroutineScope()

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Kvalita a cena") },
        actions = {
          if (accessToken != null) {
            Button(onClick = { scope.launch { AppContainer.authRepository.logout() } }) {
              Text("Odhlásit se")
            }
          } else {
            Button(onClick = { navController.navigate(ROUTE_LOGIN) { launchSingleTop = true } }) {
              Text("Přihlásit se")
            }
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
      )
    },
  ) { padding ->
    NavHost(
      navController = navController,
      startDestination = ROUTE_SCAN,
      modifier = Modifier.padding(padding),
    ) {
      composable(ROUTE_SCAN) {
        ScanScreen(
          onBarcodeDetected = { code ->
            navController.navigate("price_entry/$code")
          },
        )
      }
      composable(ROUTE_LOGIN) {
        // popBackStack() by odpojilo jen jeden záznam — nespolehlivé, pokud se "login" na
        // zásobník naskládal víckrát (navigate() sem nemá launchSingleTop). Vrátit se vždy
        // až na scan je robustnější, stejný princip jako u onScanAnother.
        LoginScreen(onLoggedIn = { navController.popBackStack(ROUTE_SCAN, inclusive = false) })
      }
      composable(ROUTE_PRICE_ENTRY) { backStackEntry ->
        val barcode = backStackEntry.arguments?.getString("barcode").orEmpty()
        PriceEntryScreen(
          barcode = barcode,
          onScanAnother = { navController.popBackStack(ROUTE_SCAN, inclusive = false) },
        )
      }
    }
  }
}
