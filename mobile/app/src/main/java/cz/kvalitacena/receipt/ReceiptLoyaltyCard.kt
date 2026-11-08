package cz.kvalitacena.receipt

import cz.kvalitacena.network.ReceiptLine
import cz.kvalitacena.network.Store

/**
 * „Nakoupeno s kartou obchodu" u účtenky (docs/soukromi.md, „Účtenky"). Na server jde jen
 * ano/ne k jedné účtence (`setReceiptLoyaltyCard`) — kterou kartu kdo má, appka serveru
 * neříká. Poslední volba pro řetězec (u samostatné prodejny pro obchod) se pamatuje jen
 * v telefonu ([ReceiptPrefs.rememberedLoyaltyCard]) a předvyplní se u další účtenky odtamtud.
 */
fun loyaltyCardKey(store: Store?): String? = when {
  store == null -> null
  store.chain != null -> "chain:${store.chain.id}"
  else -> "store:${store.id}"
}

/** Zaškrtnutí má smysl jen u účtenky, kde je aspoň jedna sleva — jinak nic nemění. */
fun hasDiscountLine(lines: List<ReceiptLine>): Boolean = lines.any { (it.discountAmount ?: 0.0) > 0.0 }
