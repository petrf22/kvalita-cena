package cz.kvalitacena.ui.receipt.detail

import cz.kvalitacena.network.OsmStoreCandidateResult
import cz.kvalitacena.ui.common.NearbyOsmStore
import cz.kvalitacena.ui.common.OsmState

/*
 * Nabídka provozoven z OpenStreetMap kolem adresy z hlavičky účtenky (`osmStoresAtAddress`).
 * Při zápisu ceny se OSM ptá podle polohy telefonu, jenže účtenka se zpracovává doma — tam
 * poloha o obchodu nic neříká, adresa vytištěná na účtence ano. Čistá logika bez Androidu, ať
 * jde otestovat (ReceiptOsmOfferTest); načítání samo žije v ReceiptDetailViewModel.
 */

/**
 * Má se nabídka načíst sama? Jen dokud se ještě neptalo ([OsmState.Idle]) nebo se kvůli chybějící
 * síti ptát nešlo ([OsmState.Offline]) — tak se dotaz zopakuje po návratu připojení. Po výsledku,
 * výpadku OSM nebo chybě už ne: další dotaz na cizí službu je volba uživatele (tlačítko „Zkusit
 * znovu"), ne smyčka při každém přerolování seznamu. Bez města na účtence se neptá vůbec.
 */
fun shouldAutoLoadOsmAtAddress(state: OsmState, hasAddress: Boolean): Boolean =
  hasAddress && (state is OsmState.Idle || state is OsmState.Offline)

/**
 * Odpověď serveru jako stav nabídky. Výpadek geokodéru či Overpassu (`status != OK`) je
 * [OsmState.Unavailable] i s případnými kandidáty — nesmí se tvářit jako „u adresy nic není".
 * Pořadí zůstává ze serveru (kandidáti podobní názvu z účtenky jsou nahoře) a vzdálenost je ta
 * serverová, tedy od geokódované ADRESY: přepočet z polohy telefonu (GeoDistance) by tu měřil
 * vzdálenost od uživatelova stolu.
 */
fun osmStateAtAddress(result: OsmStoreCandidateResult): OsmState =
  if (result.status != "OK") {
    OsmState.Unavailable
  } else {
    OsmState.Loaded(result.candidates.map { NearbyOsmStore(it, it.distanceMeters) }, result.attribution)
  }
