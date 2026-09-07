package cz.kvalitacena.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hlídání expirace access tokenu — logika, kvůli které appka přestala tvrdit „Přihlášen“
 * a přitom být pro server anonym (viz `AuthRepository.validAccessToken`). ViewModely ani
 * obrazovky se netestují (mobile/CLAUDE.md), tahle část je proto vytažená bez Androidu.
 */
class AccessTokenExpiryTest {

  @Test
  fun `cerstvy token je pouzitelny`() {
    val now = 1_000_000L
    val expiresAt = accessTokenExpiresAt(now, expiresInSec = 600)
    assertTrue(isAccessTokenUsable(expiresAt, now))
  }

  @Test
  fun `prosly token pouzitelny neni`() {
    val now = 1_000_000L
    val expiresAt = accessTokenExpiresAt(now, expiresInSec = 600)
    assertFalse(isAccessTokenUsable(expiresAt, now + 600_001))
  }

  /** Jádro opravy: token, kterému zbývá míň než rezerva, by mohl vypršet cestou na server. */
  @Test
  fun `token tesne pred vyprsenim se povazuje za neplatny`() {
    val now = 1_000_000L
    val expiresAt = accessTokenExpiresAt(now, expiresInSec = 600)
    assertTrue(isAccessTokenUsable(expiresAt, expiresAt - ACCESS_TOKEN_REFRESH_MARGIN_MS - 1))
    assertFalse(isAccessTokenUsable(expiresAt, expiresAt - ACCESS_TOKEN_REFRESH_MARGIN_MS))
    assertFalse(isAccessTokenUsable(expiresAt, expiresAt - 1))
  }

  /**
   * Server smí mít TTL kratší než rezerva (v testovacím prostředí běžné) — pak tahle funkce
   * říká „obnovit“ pořád. Rozhoduje ale jen o TOM, KDY obnovit: čerstvě vydaný token
   * `AuthRepository.validAccessToken` použije i tak, jinak by appka skončila v trvalé
   * anonymitě (zahodila by každý token hned, jak ho dostane).
   */
  @Test
  fun `ttl kratsi nez rezerva zada obnovu hned`() {
    val now = 1_000_000L
    val expiresAt = accessTokenExpiresAt(now, expiresInSec = 20)
    assertFalse(isAccessTokenUsable(expiresAt, now))
  }

  @Test
  fun `bez tokenu neni co pouzit`() {
    assertFalse(isAccessTokenUsable(null, 1_000_000L))
  }

  @Test
  fun `expirace se pocita v milisekundach`() {
    assertEquals(1_600_000L, accessTokenExpiresAt(1_000_000L, expiresInSec = 600))
  }
}
