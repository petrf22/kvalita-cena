package cz.kvalitacena.receipt

import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.ui.receipt.capture.ReceiptImageProcessor
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Účtenka zařazená k odeslání — díly už se zakrytím vypáleným do pixelů. [errorCode]
 * a [errorMessage] jsou vyplněné, když odeslání skončilo chybou, kterou nemá smysl opakovat
 * automaticky (chybí přihlášení nebo nový souhlas, denní limit — [uploadFailureAction]).
 * [ownerUid] je `publicUid` účtu, který ji zařadil — pod jiným účtem nikdy neodejde.
 * [id] slouží zároveň jako klíč idempotence nahrání (`clientUploadId`).
 */
@Serializable
data class PendingReceipt(
  val id: String,
  val ownerUid: String? = null,
  val consentVersion: Int,
  val archiveConsent: Boolean,
  val capturedAt: String,
  val queuedAt: String,
  val partCount: Int,
  val errorCode: String? = null,
  val errorMessage: String? = null,
)

class DraftCommitException(message: String) : Exception(message)

/**
 * Fronta účtenek čekajících na odeslání (`filesDir/receipts/pending/<uuid>/`) — v obchodě bez
 * signálu se účtenka jen zařadí a `ReceiptUploadWorker` ji pošle, jakmile je připojení; po
 * úspěchu adresář smaže. Na rozdíl od konceptu ([ReceiptDraftStore]) jich může čekat víc.
 */
class PendingReceiptStore(filesDir: File) {

  private val root = File(filesDir, "receipts/pending")
  private val json = Json { ignoreUnknownKeys = true }

  private val _pending = MutableStateFlow(load())
  val pending: StateFlow<List<PendingReceipt>> = _pending

  fun get(id: String): PendingReceipt? = _pending.value.firstOrNull { it.id == id }

  /** Díly v pořadí shora dolů (`part_00.jpg`, `part_01.jpg`, …). */
  fun parts(id: String): List<File> =
    File(root, id).listFiles { file -> file.name.startsWith("part_") && file.name.endsWith(".jpg") }
      ?.sortedBy { it.name }
      .orEmpty()

  /**
   * Přesune koncept do fronty: masky vypálí do pixelů (docs/soukromi.md, „Účtenky" — zakrytý
   * obsah na server vůbec nedorazí), díly očísluje podle pořadí a koncept smaže. Nepovede-li
   * se vypálit masku, nezařadí nic — raději neodeslat než odeslat nezakryté.
   */
  suspend fun commitDraft(
    draftStore: ReceiptDraftStore,
    consentVersion: Int,
    archiveConsent: Boolean,
    ownerUid: String?,
  ): PendingReceipt =
    withContext(Dispatchers.IO) {
      val draft = draftStore.draft.value
      if (draft.parts.isEmpty()) throw DraftCommitException("Koncept účtenky je prázdný")
      if (draft.parts.any { it.masks.isNotEmpty() } && !ReceiptImageProcessor.ensureLoaded()) {
        throw DraftCommitException("Zakrytí nejde vypálit — OpenCV není k dispozici")
      }
      val id = UUID.randomUUID().toString()
      val dir = File(root, id)
      dir.mkdirs()
      try {
        draft.parts.forEachIndexed { index, part ->
          val source = draftStore.fileOf(part).readBytes()
          val burned = ReceiptImageProcessor.burnMasks(source, part.masks)
          File(dir, "part_%02d.jpg".format(java.util.Locale.ROOT, index)).writeBytes(burned)
        }
        val now = OffsetDateTime.now().toString()
        val pending = PendingReceipt(
          id = id,
          ownerUid = ownerUid,
          consentVersion = consentVersion,
          archiveConsent = archiveConsent,
          capturedAt = draft.capturedAt ?: now,
          queuedAt = now,
          partCount = draft.parts.size,
        )
        writeMeta(pending)
        draftStore.clear()
        refresh()
        pending
      } catch (e: Exception) {
        dir.deleteRecursively()
        throw e
      }
    }

  fun markFailed(id: String, code: String?, message: String?) {
    val current = get(id) ?: return
    writeMeta(current.copy(errorCode = code, errorMessage = message))
    refresh()
  }

  /** Nové zkoušení (po přihlášení, novém souhlasu, druhý den po limitu) — chyba se zapomene. */
  fun clearError(id: String, consentVersion: Int? = null, archiveConsent: Boolean? = null) {
    val current = get(id) ?: return
    writeMeta(
      current.copy(
        errorCode = null,
        errorMessage = null,
        consentVersion = consentVersion ?: current.consentVersion,
        archiveConsent = archiveConsent ?: current.archiveConsent,
      ),
    )
    refresh()
  }

  fun delete(id: String) {
    File(root, id).deleteRecursively()
    refresh()
  }

  fun refresh() {
    _pending.value = load()
  }

  private fun writeMeta(pending: PendingReceipt) {
    File(root, pending.id).mkdirs()
    File(File(root, pending.id), "meta.json").writeText(json.encodeToString(PendingReceipt.serializer(), pending))
  }

  private fun load(): List<PendingReceipt> =
    root.listFiles { file -> file.isDirectory }
      ?.mapNotNull { dir ->
        runCatching { json.decodeFromString(PendingReceipt.serializer(), File(dir, "meta.json").readText()) }
          .onFailure { AppLog.e("Čekající účtenku ${dir.name} nejde přečíst", it) }
          .getOrNull()
      }
      ?.sortedBy { it.queuedAt }
      .orEmpty()
}
