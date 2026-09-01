package cz.kvalitacena.network

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pojistka proti pádu z GraphQlClient.kt:114 (`Json { ignoreUnknownKeys = true }`) —
 * `ignoreUnknownKeys` řeší jen PŘEBÝVAJÍCÍ klíče v odpovědi, ne CHYBĚJÍCÍ povinné. Když
 * fragment žádá u vnořeného typu jen podmnožinu polí, ale odpovídající DTO má pole bez
 * defaultu, kotlinx.serialization při parsování odpovědi vyhodí `MissingFieldException` —
 * a ta se ve ViewModelech schová za obecné „Něco se pokazilo"/„Hledání se nepovedlo"
 * (ui/common/ErrorMessages.kt, ui/search/SearchViewModel.kt).
 *
 * Přesně tohle se stalo v 0.5.0: `PRODUCT_SUMMARY_FIELDS` si u fotek řeklo jen
 * `photos { id thumbnailUrl }`, ale `Photo` (Dto.kt) má `url`/`width`/`height` bez defaultu
 * — hledání i "Moje příspěvky" pak spadly na jakémkoli produktu, který fotku měl.
 *
 * Test pro každý sdílený fragment ověří, že žádá všechna povinná pole cílového DTO —
 * rekurzivně i ve vnořených blocích, bez ohledu na to, jestli je obalující pole samo
 * nepovinné (relevantní je, jestli appka pole vůbec žádá, ne jestli chybí vadí i rodiči).
 */
class GraphQlFragmentContractTest {

  private data class Field(val name: String, val children: List<Field>?)

  /**
   * Fragmenty jsou syrový text, který jde beze změny do GraphQL dotazu (`GraphQlClient.execute`)
   * — na rozdíl od dřívější verze tokenizer žádné `//` komentáře neodstraňuje. Takový komentář
   * (server umí jen `#`) by appka odeslala doslova a server by celý dotaz odmítl jako syntax
   * chybu, viz [fragmentyNeobsahujiZnakyMimoGraphQl].
   */
  private fun tokenize(fragment: String): List<String> =
    Regex("[{}]|[A-Za-z_][A-Za-z0-9_]*").findAll(fragment).map { it.value }.toList()

  private fun parseFields(tokens: List<String>, pos: IntArray): List<Field> {
    val fields = mutableListOf<Field>()
    while (pos[0] < tokens.size && tokens[pos[0]] != "}") {
      val name = tokens[pos[0]]
      pos[0]++
      var children: List<Field>? = null
      if (pos[0] < tokens.size && tokens[pos[0]] == "{") {
        pos[0]++ // otevírací {
        children = parseFields(tokens, pos)
        pos[0]++ // uzavírací }
      }
      fields += Field(name, children)
    }
    return fields
  }

  private fun parseFragment(fragment: String): List<Field> = parseFields(tokenize(fragment), intArrayOf(0))

  /** Sestoupí přes List<T> na descriptor prvku T — vnořený objekt v poli má stejný požadavek na povinná pole. */
  private fun unwrapList(descriptor: SerialDescriptor): SerialDescriptor {
    var d = descriptor
    while (d.kind == StructureKind.LIST) {
      d = d.getElementDescriptor(0)
    }
    return d
  }

  private fun checkContract(fields: List<Field>, descriptor: SerialDescriptor, context: String) {
    val byName = fields.associateBy { it.name }
    for (i in 0 until descriptor.elementsCount) {
      val name = descriptor.getElementName(i)
      if (!descriptor.isElementOptional(i) && byName[name] == null) {
        fail(
          "$context: povinné pole '$name' (bez defaultu v DTO) chybí ve fragmentu — " +
            "server ho smí neposlat, appka by na tom spadla na MissingFieldException",
        )
      }
    }
    // Rekurze do KAŽDÉHO požadovaného pole s vnořenou selekcí, i nepovinného na téhle úrovni —
    // pokud ho appka žádá, cílový typ musí dostat všechna svá povinná pole.
    for (field in fields) {
      val children = field.children ?: continue
      val index = descriptor.getElementIndex(field.name)
      if (index < 0) continue
      val nested = unwrapList(descriptor.getElementDescriptor(index))
      if (nested.kind == StructureKind.CLASS) {
        checkContract(children, nested, "$context.${field.name}")
      }
    }
  }

  private fun assertContract(name: String, fragment: String, descriptor: SerialDescriptor) {
    checkContract(parseFragment(fragment), descriptor, name)
  }

  /** Všechny sdílené fragmenty — jeden seznam, ať se na nově přidaný nezapomene ani tady. */
  private val fragments = listOf(
    "PRODUCT_FIELDS" to PRODUCT_FIELDS,
    "PRODUCT_DETAIL_FIELDS" to PRODUCT_DETAIL_FIELDS,
    "PRODUCT_SUMMARY_FIELDS" to PRODUCT_SUMMARY_FIELDS,
    "SEARCH_ITEM_FIELDS" to SEARCH_ITEM_FIELDS,
    "STORE_FIELDS" to STORE_FIELDS,
    "STORE_DETAIL_FIELDS" to STORE_DETAIL_FIELDS,
    "PHOTO_FIELDS" to PHOTO_FIELDS,
    "PROFILE_FIELDS" to PROFILE_FIELDS,
    "PRICE_CURRENT_FIELDS" to PRICE_CURRENT_FIELDS,
    "CONVERTED_PRICE_FIELDS" to CONVERTED_PRICE_FIELDS,
    "PUBLICATION_STATUS_FIELDS" to PUBLICATION_STATUS_FIELDS,
    "VIEWER_FIELDS" to VIEWER_FIELDS,
    "PRODUCT_REVIEW_FIELDS" to PRODUCT_REVIEW_FIELDS,
  )

  /**
   * Fragment je jen výčet jmen polí a složených závorek — cokoli jiné (typicky `//` komentář,
   * viz historie `PRODUCT_SUMMARY_FIELDS`) appka pošle na server doslova. GraphQL nezná `//`
   * (jen řádkové `#`, a to appka do fragmentů vůbec nepoužívá), takže server celý dotaz odmítne
   * syntax chybou — na uživatelské obrazovce k nerozeznání od pádu na `MissingFieldException`,
   * který hlídají ostatní testy v týhle třídě.
   */
  @Test
  fun fragmentyNeobsahujiZnakyMimoGraphQl() {
    val allowed = Regex("^[A-Za-z0-9_{}\\s]*$")
    for ((name, fragment) in fragments) {
      if (!allowed.matches(fragment)) {
        fail("$name obsahuje znak mimo pole/složené závorky — appka ho pošle na server doslova")
      }
    }
  }

  @Test
  fun productFieldsCoverRequiredDtoFields() =
    assertContract("PRODUCT_FIELDS", PRODUCT_FIELDS, Product.serializer().descriptor)

  @Test
  fun productDetailFieldsCoverRequiredDtoFields() =
    assertContract("PRODUCT_DETAIL_FIELDS", PRODUCT_DETAIL_FIELDS, Product.serializer().descriptor)

  @Test
  fun productSummaryFieldsCoverRequiredDtoFields() =
    assertContract("PRODUCT_SUMMARY_FIELDS", PRODUCT_SUMMARY_FIELDS, ProductSummary.serializer().descriptor)

  @Test
  fun searchItemFieldsCoverRequiredDtoFields() =
    assertContract("SEARCH_ITEM_FIELDS", SEARCH_ITEM_FIELDS, ProductSearchItem.serializer().descriptor)

  @Test
  fun storeFieldsCoverRequiredDtoFields() =
    assertContract("STORE_FIELDS", STORE_FIELDS, Store.serializer().descriptor)

  @Test
  fun storeDetailFieldsCoverRequiredDtoFields() =
    assertContract("STORE_DETAIL_FIELDS", STORE_DETAIL_FIELDS, Store.serializer().descriptor)

  @Test
  fun photoFieldsCoverRequiredDtoFields() =
    assertContract("PHOTO_FIELDS", PHOTO_FIELDS, Photo.serializer().descriptor)

  @Test
  fun profileFieldsCoverRequiredDtoFields() =
    assertContract("PROFILE_FIELDS", PROFILE_FIELDS, Profile.serializer().descriptor)

  @Test
  fun priceCurrentFieldsCoverRequiredDtoFields() =
    assertContract("PRICE_CURRENT_FIELDS", PRICE_CURRENT_FIELDS, PriceCurrent.serializer().descriptor)

  @Test
  fun convertedPriceFieldsCoverRequiredDtoFields() =
    assertContract("CONVERTED_PRICE_FIELDS", CONVERTED_PRICE_FIELDS, ConvertedPrice.serializer().descriptor)

  @Test
  fun publicationStatusFieldsCoverRequiredDtoFields() =
    assertContract("PUBLICATION_STATUS_FIELDS", PUBLICATION_STATUS_FIELDS, PublicationStatus.serializer().descriptor)

  @Test
  fun viewerFieldsCoverRequiredDtoFields() =
    assertContract("VIEWER_FIELDS", VIEWER_FIELDS, Viewer.serializer().descriptor)

  @Test
  fun productReviewFieldsCoverRequiredDtoFields() =
    assertContract("PRODUCT_REVIEW_FIELDS", PRODUCT_REVIEW_FIELDS, ProductReview.serializer().descriptor)
}
