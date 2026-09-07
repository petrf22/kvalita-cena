package cz.kvalitacena.auth

/**
 * Bezpečnostní rezerva před vypršením access tokenu: token, kterému zbývá méně, se považuje
 * za neplatný a obnoví se dřív, než se s ním vyrazí na server. Bez rezervy by token stihl
 * vypršet mezi kontrolou a doručením requestu (pomalá síť, 5 s connect + 10 s read timeout
 * v `AppContainer`) a server by request tiše odbavil jako ANONYMNÍ — bez chyby, na kterou by
 * appka mohla zareagovat (viz `AuthRepository.validAccessToken`).
 */
const val ACCESS_TOKEN_REFRESH_MARGIN_MS = 30_000L

/**
 * Čistá logika vytažená z [AuthRepository], ať jde otestovat bez Androidu (konvence
 * `mobile/CLAUDE.md`). Časy jsou v milisekundách MONOTONNÍCH hodin (`SystemClock
 * .elapsedRealtime()`), ne v systémovém čase — životnost tokenu je doba, ne okamžik, a ruční
 * přenastavení hodin v telefonu (nebo skok časové zóny) by jinak platný token prohlásilo za
 * prošlý, případně naopak.
 *
 * [expiresAtMs] `null` znamená "token nemáme, nebo nevíme, dokdy platí" — obojí se řeší
 * obnovou, nikdy použitím.
 */
fun isAccessTokenUsable(expiresAtMs: Long?, nowMs: Long): Boolean =
  expiresAtMs != null && nowMs < expiresAtMs - ACCESS_TOKEN_REFRESH_MARGIN_MS

/** Okamžik vypršení tokenu vydaného [nowMs] s životností [expiresInSec] ze serveru. */
fun accessTokenExpiresAt(nowMs: Long, expiresInSec: Long): Long = nowMs + expiresInSec * 1000L
