package cz.kvalitacena.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.AppContainer
import cz.kvalitacena.R
import cz.kvalitacena.network.Viewer
import cz.kvalitacena.ui.common.UiText
import cz.kvalitacena.ui.common.toUiText
import cz.kvalitacena.ui.login.LoginScreen
import cz.kvalitacena.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Záložka "Účet" — nepřihlášený vidí dnešní [LoginScreen] jako obsah, přihlášený veřejnou
 * identitu (`me`), odkaz na editaci profilu a odhlášení. Po přihlášení (i po odhlášení, které
 * si vynutí server) se záložka sama překreslí (`isLoggedIn` je StateFlow), žádná ruční
 * navigace pryč — to bylo dřív křehké (popBackStack na sken natvrdo).
 */
@Composable
fun AccountScreen(
  onEditProfile: () -> Unit = {},
  onOpenMyContributions: () -> Unit = {},
  onOpenTerms: () -> Unit = {},
  onOpenPrivacy: () -> Unit = {},
  onOpenReceipts: () -> Unit = {},
) {
  val sessionKnown by AppContainer.authRepository.sessionKnown.collectAsState()
  val isLoggedIn by AppContainer.authRepository.isLoggedIn.collectAsState()

  // Dokud se nepřečte uložený refresh token (Keystore, desítky ms — na hlavní vlákno při startu
  // nepatří), není `isLoggedIn = false` odpověď, ale "ještě nevím". Bez tohohle čekání by
  // přihlášenému problikl přihlašovací formulář.
  if (!sessionKnown) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
  } else if (!isLoggedIn) {
    LoginScreen(onLoggedIn = {}, onOpenTerms = onOpenTerms, onOpenPrivacy = onOpenPrivacy)
  } else {
    LoggedInContent(onEditProfile, onOpenMyContributions, onOpenReceipts)
  }
}

@Composable
private fun LoggedInContent(onEditProfile: () -> Unit, onOpenMyContributions: () -> Unit, onOpenReceipts: () -> Unit) {
  val scope = rememberCoroutineScope()
  var viewer by remember { mutableStateOf<Viewer?>(null) }
  var loading by remember { mutableStateOf(true) }
  var loadError by remember { mutableStateOf<UiText?>(null) }
  // Odznak „nové ke kontrole" (receiptSummary) — výpadek jen odznak schová, účet funguje dál.
  var unseenReceipts by remember { mutableIntStateOf(0) }

  LaunchedEffect(Unit) {
    runCatching { AppContainer.graphQlClient.receiptSummary() }.onSuccess {
      unseenReceipts = it.unseenCount
      AppContainer.receiptPrefs.serverConsentVersion = it.consentVersion
    }
  }

  LaunchedEffect(Unit) {
    // Opakovat dotaz tady není potřeba — `GraphQlClient.me()` si odmítnutý token vyřeší sám
    // (obnova + druhý pokus) a když ani ta neprojde, session zanikne a rodičovská záložka
    // překreslí přihlašovací obrazovku. Chybu sítě ale neschovávat za fallback "Přihlášen":
    // vypadala pak stejně jako odpověď serveru, který nás nezná, i když dotaz vůbec neodešel.
    try {
      viewer = AppContainer.graphQlClient.me()
    } catch (e: Exception) {
      loadError = e.toUiText()
    }
    loading = false
  }

  Column(modifier = Modifier.fillMaxSize().padding(Spacing.xl)) {
    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(Spacing.lg))

    if (loading) {
      CircularProgressIndicator()
    } else {
      Text(
        viewer?.publicHandle ?: stringResource(R.string.account_logged_in_fallback),
        style = MaterialTheme.typography.titleMedium,
      )
      viewer?.displayName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
      loadError?.let {
        Text(
          it.asString(),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }
    }

    Spacer(Modifier.height(Spacing.xl))
    OutlinedButton(onClick = onEditProfile) {
      Text(stringResource(R.string.account_edit_profile))
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(onClick = onOpenMyContributions) {
      Text(stringResource(R.string.account_my_contributions))
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(onClick = onOpenReceipts) {
      Icon(painterResource(R.drawable.ic_receipt), contentDescription = null)
      Spacer(Modifier.width(Spacing.sm))
      Text(stringResource(R.string.account_receipts))
      if (unseenReceipts > 0) {
        Spacer(Modifier.width(Spacing.sm))
        Badge { Text(unseenReceipts.toString()) }
      }
    }
    Spacer(Modifier.height(Spacing.sm))
    Button(onClick = { scope.launch { AppContainer.authRepository.logout() } }) {
      Text(stringResource(R.string.account_logout))
    }
  }
}
