package cz.kvalitacena.ui.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Proof-of-work řešič pro formulář zpětné vazby (docs/nasazeni.md, obrana proti spamu) — náhrada
 * CAPTCHY, kterou appka nesmí použít (docs/soukromi.md, žádné externí skripty/CDN třetí strany).
 * Definice musí být BIT-PŘESNĚ stejná jako backend (`FeedbackChallengeService`) a web
 * (`shared/proof-of-work.ts`): hledá se nejmenší nezáporné celé `nonce`, pro které má
 * SHA-256(UTF8(salt + ":" + nonce)) [difficulty] vedoucích nulových bitů.
 */
object ProofOfWork {

  suspend fun solve(salt: String, difficulty: Int): String = withContext(Dispatchers.Default) {
    computeNonce(salt, difficulty)
  }

  private fun computeNonce(salt: String, difficulty: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var nonce = 0L
    while (true) {
      val hash = digest.digest("$salt:$nonce".toByteArray(Charsets.UTF_8))
      if (hasLeadingZeroBits(hash, difficulty)) return nonce.toString()
      nonce++
    }
  }

  fun hasLeadingZeroBits(hash: ByteArray, bits: Int): Boolean {
    val fullBytes = bits / 8
    for (i in 0 until fullBytes) {
      if (hash[i].toInt() != 0) return false
    }
    val remainingBits = bits % 8
    if (remainingBits == 0 || fullBytes >= hash.size) return true
    val mask = (0xFF shl (8 - remainingBits)) and 0xFF
    return (hash[fullBytes].toInt() and mask) == 0
  }
}
