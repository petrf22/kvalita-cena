package cz.kvalitacena.ui.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.kvalitacena.AppContainer
import cz.kvalitacena.network.Viewer
import cz.kvalitacena.ui.login.LoginScreen
import kotlinx.coroutines.launch

/**
 * Záložka "Účet" — nepřihlášený vidí dnešní [LoginScreen] jako obsah, přihlášený veřejnou
 * identitu (`me`) a odhlášení. Po přihlášení se záložka sama překreslí (accessToken je
 * StateFlow), žádná ruční navigace pryč — to bylo dřív křehké (popBackStack na sken natvrdo).
 */
@Composable
fun AccountScreen() {
  val accessToken by AppContainer.authRepository.accessToken.collectAsState()

  if (accessToken == null) {
    LoginScreen(onLoggedIn = {})
  } else {
    LoggedInContent()
  }
}

@Composable
private fun LoggedInContent() {
  val scope = rememberCoroutineScope()
  var viewer by remember { mutableStateOf<Viewer?>(null) }
  var loading by remember { mutableStateOf(true) }

  LaunchedEffect(Unit) {
    viewer = runCatching { AppContainer.graphQlClient.me() }.getOrNull()
    loading = false
  }

  Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
    Text("Účet", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))

    if (loading) {
      CircularProgressIndicator()
    } else {
      Text(viewer?.publicHandle ?: "Přihlášen", style = MaterialTheme.typography.titleMedium)
      viewer?.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    Spacer(Modifier.height(24.dp))
    Button(onClick = { scope.launch { AppContainer.authRepository.logout() } }) {
      Text("Odhlásit se")
    }
  }
}
