package cz.kvalitacena.ui.contributions

import cz.kvalitacena.R
import cz.kvalitacena.network.PublicationStatus
import cz.kvalitacena.ui.common.UiText

/**
 * Kdy se vlastní záznam propaguje globálně (docs/datovy-model.md, "Uživatelská vrstva nad
 * globálními daty"; prahy v docs/reputace.md) — čistá funkce mimo Compose, ať jde otestovat
 * JUnitem bez instrumentace (stejný vzor jako PriceChartGeometry/StoreLabel). Webový protějšek:
 * frontend shared/publication-status-text.ts.
 *
 * PRODUCT/STORE mají u AWAITING_CONFIRMATIONS jiný text (bezkódové zboží najde kdokoli skenem
 * kódu, PENDING obchod nenajde nikdo) — viz docs/reputace.md. OBSERVATION nese vlastní text pro
 * PUBLIC/AWAITING, protože cena sama žádný práh nemá, jen dědí stav od blokujícího zboží/
 * obchodu. EDIT (úprava cizího záznamu) je ve výpisu vždy PENDING_MERGE.
 */
enum class PublicationRecordKind { PRODUCT, STORE, OBSERVATION, EDIT }

fun publicationStateLabel(state: String): Int = when (state) {
  "AWAITING_CONFIRMATIONS" -> R.string.my_contributions_state_awaiting_confirmations
  "HIDDEN_AFTER_FLAGS" -> R.string.my_contributions_state_hidden_after_flags
  "PENDING_MERGE" -> R.string.my_contributions_state_pending_merge
  else -> R.string.my_contributions_state_public
}

fun publicationStatusText(status: PublicationStatus, kind: PublicationRecordKind): UiText {
  return when (status.state) {
    "AWAITING_CONFIRMATIONS" -> {
      val required = status.confirmationsRequired ?: 0
      val received = status.confirmationsReceived ?: 0
      val resId = when (kind) {
        PublicationRecordKind.PRODUCT -> R.string.my_contributions_status_awaiting_product
        PublicationRecordKind.STORE -> R.string.my_contributions_status_awaiting_store
        PublicationRecordKind.OBSERVATION -> R.string.my_contributions_status_awaiting_observation
        PublicationRecordKind.EDIT -> R.string.my_contributions_status_pending_merge_edit
      }
      UiText.Res(resId, listOf(required, received))
    }
    "HIDDEN_AFTER_FLAGS" -> UiText.Res(
      when (kind) {
        PublicationRecordKind.PRODUCT -> R.string.my_contributions_status_hidden_product
        PublicationRecordKind.STORE -> R.string.my_contributions_status_hidden_store
        PublicationRecordKind.OBSERVATION -> R.string.my_contributions_status_hidden_observation
        PublicationRecordKind.EDIT -> R.string.my_contributions_status_pending_merge_edit
      },
    )
    "PENDING_MERGE" -> UiText.Res(R.string.my_contributions_status_pending_merge_edit)
    else -> UiText.Res(
      when (kind) {
        PublicationRecordKind.PRODUCT -> R.string.my_contributions_status_public_product
        PublicationRecordKind.STORE -> R.string.my_contributions_status_public_store
        PublicationRecordKind.OBSERVATION -> R.string.my_contributions_status_public_observation
        PublicationRecordKind.EDIT -> R.string.my_contributions_status_pending_merge_edit
      },
    )
  }
}
