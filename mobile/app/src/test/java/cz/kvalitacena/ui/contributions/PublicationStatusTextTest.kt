package cz.kvalitacena.ui.contributions

import cz.kvalitacena.R
import cz.kvalitacena.network.PublicationStatus
import cz.kvalitacena.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/** Čistá funkce mimo Compose — stejný vzor jako StoreLabelTest/PriceChartGeometryTest. */
class PublicationStatusTextTest {

  @Test
  fun draftProductReportsExactConfirmationCounts() {
    val status = PublicationStatus("AWAITING_CONFIRMATIONS", confirmationsReceived = 1, confirmationsRequired = 3)
    val text = publicationStatusText(status, PublicationRecordKind.PRODUCT)
    assertEquals(
      UiText.Res(R.string.my_contributions_status_awaiting_product, listOf(3, 1)),
      text,
    )
  }

  @Test
  fun pendingStoreUsesDifferentKeyThanProduct() {
    val status = PublicationStatus("AWAITING_CONFIRMATIONS", confirmationsReceived = 0, confirmationsRequired = 3)
    val text = publicationStatusText(status, PublicationRecordKind.STORE)
    assertEquals(
      UiText.Res(R.string.my_contributions_status_awaiting_store, listOf(3, 0)),
      text,
    )
  }

  @Test
  fun missingConfirmationNumbersDefaultToZero() {
    val status = PublicationStatus("AWAITING_CONFIRMATIONS")
    val text = publicationStatusText(status, PublicationRecordKind.OBSERVATION)
    assertEquals(
      UiText.Res(R.string.my_contributions_status_awaiting_observation, listOf(0, 0)),
      text,
    )
  }

  @Test
  fun hiddenAfterFlagsHasNoParams() {
    val status = PublicationStatus("HIDDEN_AFTER_FLAGS")
    val text = publicationStatusText(status, PublicationRecordKind.PRODUCT)
    assertEquals(UiText.Res(R.string.my_contributions_status_hidden_product), text)
  }

  @Test
  fun publicFallsBackForUnknownState() {
    val status = PublicationStatus("PUBLIC", verified = true)
    val text = publicationStatusText(status, PublicationRecordKind.STORE)
    assertEquals(UiText.Res(R.string.my_contributions_status_public_store), text)
  }

  @Test
  fun editIsAlwaysThePendingMergeKey() {
    val status = PublicationStatus("PENDING_MERGE")
    val text = publicationStatusText(status, PublicationRecordKind.EDIT)
    assertEquals(UiText.Res(R.string.my_contributions_status_pending_merge_edit), text)
  }

  @Test
  fun stateLabelMapsAllFourStates() {
    assertEquals(R.string.my_contributions_state_public, publicationStateLabel("PUBLIC"))
    assertEquals(
      R.string.my_contributions_state_awaiting_confirmations,
      publicationStateLabel("AWAITING_CONFIRMATIONS"),
    )
    assertEquals(R.string.my_contributions_state_hidden_after_flags, publicationStateLabel("HIDDEN_AFTER_FLAGS"))
    assertEquals(R.string.my_contributions_state_pending_merge, publicationStateLabel("PENDING_MERGE"))
  }
}
