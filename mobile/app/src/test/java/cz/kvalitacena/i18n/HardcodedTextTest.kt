package cz.kvalitacena.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hlídá, že se do UI vrstvy nevrátí zadrátovaný český string literál (docs/lokalizace.md) —
 * regresní pojistka poté, co balík L extrakci dokončil. Komentáře/KDoc se před hledáním
 * odstraní (v projektu zůstávají česky, CLAUDE.md), takže nejsou falešným nálezem. [ALLOWLIST]
 * jsou soubory s výjimkou z dobrého důvodu: `LocaleController.kt` má endonyma (nikdy se
 * nepřekládají), `GraphQlClient.kt`/`MediaClient.kt`/`AuthRepository.kt` mají technické
 * `TransportException` zprávy, které appka nikdy nezobrazí jako lokalizovaný text (mapují se
 * na obecný fallback přes `Throwable.toUiText()`, viz `ui/common/ErrorMessages.kt`).
 */
class HardcodedTextTest {

  private val sourceRoot = File("src/main/java")

  private val allowlist = setOf(
    "LocaleController.kt",
    "GraphQlClient.kt",
    "MediaClient.kt",
    "AuthRepository.kt",
  )

  private val blockComment = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
  private val lineComment = Regex("//.*")
  private val czechStringLiteral = Regex(
    "\"[^\"]*[ěščřžýáíéúůťďňĚŠČŘŽÝÁÍÉÚŮŤĎŇ][^\"]*\"",
  )

  @Test
  fun uiLayerDoesNotHardcodeCzechStringLiterals() {
    val violations = sourceRoot.walkTopDown()
      .filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
      .flatMap { file ->
        val withoutComments = file.readText().replace(blockComment, "").replace(lineComment, "")
        czechStringLiteral.findAll(withoutComments).map { match ->
          "${file.relativeTo(sourceRoot)}: ${match.value}"
        }
      }
      .toList()

    assertEquals(
      "Nový český text natvrdo v UI vrstvě mimo allowlist — přidej do strings.xml"
        + " (docs/lokalizace.md), nebo soubor přidej do allowlistu, pokud jde o skutečně"
        + " technický text, který appka nikdy nezobrazí jako lokalizovaný text:\n"
        + violations.joinToString("\n"),
      emptyList<String>(),
      violations,
    )
  }
}
