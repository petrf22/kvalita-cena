package cz.kvalitacena.network

import cz.kvalitacena.ui.common.SELECTABLE_PRICE_KINDS
import cz.kvalitacena.ui.product.NET_CONTENT_UOM_CHOICES
import cz.kvalitacena.ui.product.unitBaseForUom
import cz.kvalitacena.ui.receipt.list.ReceiptListFilter
import cz.kvalitacena.ui.search.SortOption
import graphql.language.EnumTypeDefinition
import graphql.parser.Parser
import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import graphql.schema.idl.UnExecutableSchemaGenerator
import graphql.validation.Validator
import java.io.File
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Kontrakt mobil ↔ server: každý dotaz, který [GraphQlClient] posílá, musí projít validací
 * proti schématu backendu (`backend/src/main/resources/graphql/schema.graphqls`).
 *
 * Web má `graphql-codegen` a CI guard; mobil píše dotazy ručně jako text a enumy drží jako
 * `String`, takže přejmenované nebo smazané pole na serveru se dřív projevilo až pádem u
 * uživatele — a to u verze, kterou už nejde stáhnout zpět. Tenhle test tu samou chybu chytí
 * v CI (`:app:testDebugUnitTest`) u PR, který schéma mění.
 *
 * Dotazy se berou ze ZDROJOVÉHO TEXTU `GraphQlClient.kt`, ne voláním metod: žijí jako lokální
 * řetězce uvnitř šedesáti funkcí a volat každou s vymyšlenými argumenty proti falešnému HTTP
 * klientovi by znamenalo seznam, na který se s novou metodou zapomene. Čtení zdrojáku najde
 * i dotaz, o kterém test neví — a [kazdeVolaniExecuteMaVeSveFunkciNalezenyDotaz] hlídá, že se
 * hledání samo tiše nerozbilo.
 *
 * Doplňuje [GraphQlFragmentContractTest]: ten hlídá fragment ↔ DTO (chybějící povinné pole
 * shodí deserializaci), tenhle fragment ↔ server.
 */
class GraphQlSchemaContractTest {

  private data class Operation(val line: Int, val text: String)

  private val clientSource: String = locate(
    "src/main/java/cz/kvalitacena/network/GraphQlClient.kt",
    "app/src/main/java/cz/kvalitacena/network/GraphQlClient.kt",
  ).readText()

  private val registry: TypeDefinitionRegistry = SchemaParser().parse(
    locate(
      "../../backend/src/main/resources/graphql/schema.graphqls",
      "../backend/src/main/resources/graphql/schema.graphqls",
    ),
  )

  /** Schéma bez resolverů — na validaci dotazů stačí, vlastní skaláry si doplní samo. */
  private val schema: GraphQLSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(registry)

  /** Pracovní adresář testu je modul `app`; druhá cesta kryje spuštění z kořene `mobile/`. */
  private fun locate(vararg candidates: String): File =
    candidates.map { File(it) }.firstOrNull { it.isFile }
      ?: error("Nenalezeno: ${candidates.joinToString()} (pracovní adresář ${File(".").absolutePath})")

  private val rawString = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
  private val plainString = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")

  /** Všechny řetězcové literály souboru s číslem řádku, na kterém začínají. */
  private fun stringLiterals(source: String): List<Operation> {
    val literals = mutableListOf<Operation>()
    fun lineOf(offset: Int) = source.substring(0, offset).count { it == '\n' } + 1
    // Nejdřív trojité uvozovky; jejich obsah se vymaže (délka zůstává kvůli číslům řádků),
    // aby uvozovka uvnitř dotazu nezaložila falešný obyčejný řetězec.
    val withoutRaw = StringBuilder(source)
    for (match in rawString.findAll(source)) {
      literals += Operation(lineOf(match.range.first), match.groupValues[1])
      for (i in match.range) if (withoutRaw[i] != '\n') withoutRaw[i] = ' '
    }
    for (match in plainString.findAll(withoutRaw)) {
      literals += Operation(lineOf(match.range.first), match.groupValues[1])
    }
    return literals
  }

  /** `internal val NAZEV_FIELDS = "…"` — sdílené výběry polí vkládané do dotazů přes `$NAZEV`. */
  private val fragments: Map<String, String> =
    Regex("internal val ([A-Z][A-Z0-9_]*) = (\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\")", RegexOption.DOT_MATCHES_ALL)
      .findAll(clientSource)
      .associate { it.groupValues[1] to it.groupValues[3].ifEmpty { it.groupValues[4] } }

  /**
   * Kotlin šablona → text, který appka skutečně odešle: `${'$'}` je doslovný dolar (GraphQL
   * proměnná), `$VELKYMI_PISMENY` vložený fragment. Cokoli jiného s `${…}` test neumí vyhodnotit
   * a raději spadne, než aby dotaz validoval jinak, než jak odejde.
   */
  private fun render(template: String, depth: Int = 0): String {
    check(depth < 10) { "Fragmenty se odkazují v kruhu" }
    val dollar = "\u0000"
    var text = template.replace("\${'$'}", dollar)
    text = Regex("\\$([A-Z][A-Z0-9_]*)").replace(text) { match ->
      val name = match.groupValues[1]
      render(fragments[name] ?: error("Dotaz vkládá \$$name, ale takový fragment v GraphQlClient.kt není"), depth + 1)
    }
    check(!text.contains("\${")) { "Neznámá šablona \${…} v dotazu: ${text.trim().take(120)}" }
    return text.replace(dollar, "$")
  }

  /**
   * Začátek operace: `query(…)`/`query {`/`mutation(…)`, nebo zkrácený zápis dotazu `{ me { … } }`.
   * Holé slovo `"query"` (klíč proměnné v `put("query", …)`) operace není.
   */
  private val operationStart = Regex("^(?:(?:query|mutation)\\s*[({]|\\{)")

  private val operations: List<Operation> =
    stringLiterals(clientSource)
      .filter { operationStart.containsMatchIn(it.text.trim()) }
      .map { Operation(it.line, render(it.text).trim()) }

  @Test
  fun kazdyDotazProjdeValidaciProtiSchematuServeru() {
    val problems = mutableListOf<String>()
    for (operation in operations) {
      val errors = try {
        Validator().validateDocument(schema, Parser().parseDocument(operation.text), Locale.ENGLISH)
          .map { it.message }
      } catch (e: Exception) {
        listOf("dotaz nejde přečíst: ${e.message}")
      }
      if (errors.isNotEmpty()) {
        val head = operation.text.lineSequence().take(2).joinToString(" ") { it.trim() }.take(140)
        problems += "GraphQlClient.kt:${operation.line}  $head\n" + errors.joinToString("\n") { "    - $it" }
      }
    }
    if (problems.isNotEmpty()) {
      fail(
        "Dotazy mobilní appky neodpovídají schématu serveru (schema.graphqls). Buď se změnilo " +
          "schéma a dotaz je potřeba upravit — a pokud jde o nekompatibilní změnu, zvednout " +
          "min-android-version (docs/vydani.md) —, nebo je v dotazu překlep:\n\n" +
          problems.joinToString("\n\n"),
      )
    }
  }

  /**
   * Pojistka proti tichému minutí: kdyby se dotazy začaly skládat způsobem, který hledání
   * v literálech nevidí (spojování řetězců, dotaz předaný parametrem, dotaz v jiném souboru),
   * první test by „prošel" nad menší množinou. Každé volání `execute(` proto musí mít nalezený
   * dotaz ve své vlastní funkci — mezi její deklarací a voláním.
   */
  @Test
  fun kazdeVolaniExecuteMaVeSveFunkciNalezenyDotaz() {
    fun lineOf(offset: Int) = clientSource.substring(0, offset).count { it == '\n' } + 1
    val functionLines = Regex("\\bfun\\b").findAll(clientSource).map { lineOf(it.range.first) }.toList()
    // `execute(` jako volání vlastní metody — bez tečky před (to je OkHttp `.execute()`)
    // a mimo samotnou deklaraci `fun <T> execute(`.
    val callLines = Regex("(?<![.\\w])execute\\(").findAll(clientSource)
      .filterNot { clientSource.substring(0, it.range.first).trimEnd().endsWith("<T>") }
      .map { lineOf(it.range.first) }
      .toList()
    assertTrue("V GraphQlClient.kt se nenašla žádná volání execute( — změnil se tvar souboru?", callLines.size > 10)

    val orphans = callLines.filter { call ->
      val functionStart = functionLines.last { it <= call }
      operations.none { it.line in functionStart..call }
    }
    assertTrue(
      "Volání execute( na řádcích $orphans nemají ve své funkci dotaz, který by test viděl jako " +
        "řetězcový literál začínající query/mutation/{ — takový dotaz se proti schématu neověřuje.",
      orphans.isEmpty(),
    )
  }

  private fun enumValues(name: String): Set<String> {
    val definition = registry.getType(name, EnumTypeDefinition::class.java)
      .orElseThrow { AssertionError("Enum $name ve schématu není") }
    return definition.enumValueDefinitions.map { it.name }.toSet()
  }

  private fun assertKnown(what: String, enumName: String, values: Collection<String>) {
    val unknown = values - enumValues(enumName)
    assertTrue("$what posílá hodnoty, které enum $enumName na serveru nezná: $unknown", unknown.isEmpty())
  }

  /**
   * Enumy drží appka jako `String` (Dto.kt), takže překlep nebo hodnotu smazanou na serveru
   * kompilátor nechytí. Pokryté jsou seznamy, ze kterých uživatel vybírá a které jdou do
   * mutací; literály rozeseté po obrazovkách (`"PROMO"` v podmínkách) tenhle test nevidí.
   */
  @Test
  fun hodnotyEnumuPosilaneServeruExistujiVeSchematu() {
    assertKnown("Výběr druhu ceny", "PriceKind", SELECTABLE_PRICE_KINDS)
    assertKnown("Řazení hledání", "ProductSort", SortOption.entries.map { it.value })
    assertKnown("Výběr jednotky gramáže", "NetContentUom", NET_CONTENT_UOM_CHOICES)
    assertKnown("Filtr seznamu účtenek", "ReceiptListFilter", ReceiptListFilter.entries.map { it.name })
    assertKnown(
      "unitBaseForUom",
      "UnitBase",
      (NET_CONTENT_UOM_CHOICES + listOf(null)).map { unitBaseForUom(it) },
    )
  }
}
