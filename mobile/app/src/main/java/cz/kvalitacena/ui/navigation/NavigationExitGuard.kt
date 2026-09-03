package cz.kvalitacena.ui.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import cz.kvalitacena.R

class NavigationExitGuardState {
  var dirty by mutableStateOf(false)
    private set
  var dialogVisible by mutableStateOf(false)
    private set
  private var pendingNavigation: (() -> Unit)? = null

  fun reportDirty(value: Boolean) {
    dirty = value
  }

  fun requestNavigation(action: () -> Unit) {
    if (!dirty) {
      action()
      return
    }
    pendingNavigation = action
    dialogVisible = true
  }

  fun cancelNavigation() {
    pendingNavigation = null
    dialogVisible = false
  }

  fun discardAndNavigate() {
    val action = pendingNavigation
    pendingNavigation = null
    dialogVisible = false
    dirty = false
    action?.invoke()
  }

  fun clear() {
    dirty = false
    dialogVisible = false
    pendingNavigation = null
  }
}

val LocalNavigationExitGuard = staticCompositionLocalOf<NavigationExitGuardState> {
  error("NavigationExitGuardState nebyl poskytnut")
}

@Composable
fun ReportUnsavedChanges(dirty: Boolean) {
  val guard = LocalNavigationExitGuard.current
  LaunchedEffect(dirty) { guard.reportDirty(dirty) }
  DisposableEffect(Unit) {
    onDispose { guard.reportDirty(false) }
  }
}

@Composable
fun NavigationExitGuardDialog(state: NavigationExitGuardState) {
  if (!state.dialogVisible) return
  AlertDialog(
    onDismissRequest = state::cancelNavigation,
    title = { Text(stringResource(R.string.unsaved_changes_title)) },
    text = { Text(stringResource(R.string.unsaved_changes_message)) },
    confirmButton = {
      TextButton(onClick = state::discardAndNavigate) {
        Text(stringResource(R.string.unsaved_changes_discard))
      }
    },
    dismissButton = {
      TextButton(onClick = state::cancelNavigation) {
        Text(stringResource(R.string.common_cancel))
      }
    },
  )
}
