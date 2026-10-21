package cz.kvalitacena.receipt

import cz.kvalitacena.crash.AppLog
import cz.kvalitacena.ui.receipt.capture.EncodedPart
import cz.kvalitacena.ui.receipt.capture.MaskRect
import cz.kvalitacena.ui.receipt.capture.defaultMaskProposal
import cz.kvalitacena.ui.receipt.capture.moved
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Jeden díl rozpracované účtenky — soubor v `filesDir/receipts/draft/` a masky nad ním. */
@Serializable
data class DraftPart(
  val file: String,
  val width: Int,
  val height: Int,
  val masks: List<MaskRect> = emptyList(),
)

@Serializable
data class ReceiptDraft(
  val parts: List<DraftPart> = emptyList(),
  /** Kdy uživatel začal fotit (ISO-8601 s offsetem) — jde na server jako `capturedAt`. */
  val capturedAt: String? = null,
  /** Díl, na který appka naposledy navrhla výchozí zakrytí (spodní pás posledního dílu). */
  val proposedMaskFor: String? = null,
)

/**
 * Rozpracovaná účtenka mezi snímáním a odesláním — díly jako soubory v úložišti appky (ne
 * v galerii ani v cache, kterou systém smí kdykoli smazat) a pořadí s maskami v `draft.json`,
 * takže přežije i zabití procesu mezi focením a kontrolou. Jediný rozpracovaný koncept naráz;
 * odesláním ([PendingReceiptStore.commitDraft]) se přesune do fronty a koncept se vyčistí.
 */
class ReceiptDraftStore(filesDir: File) {

  private val dir = File(filesDir, "receipts/draft")
  private val metaFile = File(dir, "draft.json")
  private val json = Json { ignoreUnknownKeys = true }

  private val _draft = MutableStateFlow(load())
  val draft: StateFlow<ReceiptDraft> = _draft

  fun fileOf(part: DraftPart): File = File(dir, part.file)

  @Synchronized
  fun addPart(part: EncodedPart): DraftPart {
    val stored = write(part)
    update { it.copy(parts = it.parts + stored, capturedAt = it.capturedAt ?: OffsetDateTime.now().toString()) }
    return stored
  }

  /** „Nafotit znovu" — nový snímek na místo dílu [index]; masky starého dílu neplatí. */
  @Synchronized
  fun replacePart(index: Int, part: EncodedPart) {
    val old = _draft.value.parts.getOrNull(index) ?: return
    val stored = write(part)
    update { draft -> draft.copy(parts = draft.parts.mapIndexed { i, p -> if (i == index) stored else p }) }
    File(dir, old.file).delete()
  }

  @Synchronized
  fun removePart(index: Int) {
    val old = _draft.value.parts.getOrNull(index) ?: return
    update { draft ->
      val remaining = draft.parts.filterIndexed { i, _ -> i != index }
      // Prázdný koncept začíná znovu — čas focení i návrh zakrytí patří nové účtence.
      if (remaining.isEmpty()) ReceiptDraft() else draft.copy(parts = remaining)
    }
    File(dir, old.file).delete()
  }

  @Synchronized
  fun movePart(from: Int, to: Int) {
    update { it.copy(parts = it.parts.moved(from, to)) }
  }

  @Synchronized
  fun setMasks(index: Int, masks: List<MaskRect>) {
    update { draft -> draft.copy(parts = draft.parts.mapIndexed { i, p -> if (i == index) p.copy(masks = masks) else p }) }
  }

  /**
   * Výchozí návrh zakrytí (docs/soukromi.md, „Účtenky") — spodní pás POSLEDNÍHO dílu, kde bývá
   * platba kartou a věrnostní karta. Když mezitím přibyl další díl, nezměněný návrh se ze
   * starého dílu stáhne: uprostřed účtenky by zakryl položky, ne kartu.
   */
  @Synchronized
  fun proposeDefaultMask() {
    val current = _draft.value
    val last = current.parts.lastOrNull() ?: return
    if (current.proposedMaskFor == last.file) return
    val proposal = defaultMaskProposal()
    update { draft ->
      draft.copy(
        parts = draft.parts.map { part ->
          when {
            part.file == last.file && part.masks.isEmpty() -> part.copy(masks = listOf(proposal))
            part.file == draft.proposedMaskFor && part.masks == listOf(proposal) -> part.copy(masks = emptyList())
            else -> part
          }
        },
        proposedMaskFor = last.file,
      )
    }
  }

  @Synchronized
  fun clear() {
    dir.listFiles()?.forEach { it.delete() }
    _draft.value = ReceiptDraft()
  }

  private fun write(part: EncodedPart): DraftPart {
    dir.mkdirs()
    val name = "${UUID.randomUUID()}.jpg"
    File(dir, name).writeBytes(part.jpeg)
    return DraftPart(name, part.width, part.height)
  }

  private fun update(transform: (ReceiptDraft) -> ReceiptDraft) {
    val next = transform(_draft.value)
    _draft.value = next
    runCatching {
      dir.mkdirs()
      metaFile.writeText(json.encodeToString(ReceiptDraft.serializer(), next))
    }.onFailure { AppLog.e("Koncept účtenky se nepodařilo uložit", it) }
  }

  /** Díly, jejichž soubor mezitím zmizel, se zahodí — koncept se nesmí rozbít na chybějícím souboru. */
  private fun load(): ReceiptDraft {
    val loaded = runCatching { json.decodeFromString(ReceiptDraft.serializer(), metaFile.readText()) }
      .getOrNull() ?: return ReceiptDraft()
    return loaded.copy(parts = loaded.parts.filter { File(dir, it.file).isFile })
  }
}
