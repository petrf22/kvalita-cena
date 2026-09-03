package cz.kvalitacena.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationExitGuardStateTest {

  @Test
  fun cleanFormNavigatesImmediately() {
    val state = NavigationExitGuardState()
    var navigated = false

    state.requestNavigation { navigated = true }

    assertTrue(navigated)
    assertFalse(state.dialogVisible)
  }

  @Test
  fun dirtyFormWaitsForExplicitDiscard() {
    val state = NavigationExitGuardState()
    var navigated = false
    state.reportDirty(true)

    state.requestNavigation { navigated = true }

    assertFalse(navigated)
    assertTrue(state.dialogVisible)
    state.discardAndNavigate()
    assertTrue(navigated)
    assertFalse(state.dirty)
  }

  @Test
  fun cancellingKeepsChangesAndDoesNotNavigate() {
    val state = NavigationExitGuardState()
    var navigated = false
    state.reportDirty(true)
    state.requestNavigation { navigated = true }

    state.cancelNavigation()

    assertFalse(navigated)
    assertTrue(state.dirty)
    assertFalse(state.dialogVisible)
  }
}
