package cz.kvalitacena.ui.receipt.list

import cz.kvalitacena.network.ReceiptFilterCounts

/**
 * Skupina seznamu účtenek podle stavu — zrcadlí `enum ReceiptListFilter` ze schématu
 * (`myReceipts(filter:)`); do serveru jde jako [name], protože mobil enumy z GraphQL mapuje
 * ručně na `String` (`network/Dto.kt`). Skupiny se nepřekrývají a dohromady dávají [ALL]; co do
 * které patří, rozhoduje výhradně server (`ReceiptRepository`), klient stav účtenky sám
 * nezařazuje.
 */
enum class ReceiptListFilter { ALL, TO_REVIEW, PROCESSING, DONE, FAILED }

/**
 * Filtr prvního dotazu, dokud výchozí volba není rozhodnutá. Je to [ReceiptListFilter.TO_REVIEW],
 * protože ten je výchozí volbou v běžném případě — druhý dotaz pak padne jen tehdy, když nic
 * nečeká na vyřízení ([initialReceiptFilter]).
 */
val FIRST_RECEIPT_FILTER = ReceiptListFilter.TO_REVIEW

/**
 * Výchozí volba po otevření seznamu: „K vyřízení", když nějaká účtenka čeká na párování —
 * v plném seznamu by mohla skončit za „Načíst další" a uživatel by ji přehlédl. Když nic
 * nečeká, „Vše" (prázdný filtr jako první pohled by vypadal, že účtenky zmizely).
 */
fun initialReceiptFilter(counts: ReceiptFilterCounts): ReceiptListFilter =
  if (counts.toReview > 0) ReceiptListFilter.TO_REVIEW else ReceiptListFilter.ALL

/** Počet účtenek ve skupině — číslo na štítku filtru. */
fun ReceiptFilterCounts.countFor(filter: ReceiptListFilter): Int = when (filter) {
  ReceiptListFilter.ALL -> all
  ReceiptListFilter.TO_REVIEW -> toReview
  ReceiptListFilter.PROCESSING -> processing
  ReceiptListFilter.DONE -> done
  ReceiptListFilter.FAILED -> failed
}
