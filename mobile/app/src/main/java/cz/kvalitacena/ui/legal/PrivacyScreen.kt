package cz.kvalitacena.ui.legal

import androidx.compose.runtime.Composable
import cz.kvalitacena.R

/** Zásady ochrany osobních údajů — zrcadlí docs/zasady-ochrany-osobnich-udaju.md, viz [LegalScreen]. */
@Composable
fun PrivacyScreen(onDone: () -> Unit) {
  LegalScreen(
    titleRes = R.string.privacy_title,
    headingsRes = R.array.privacy_headings,
    paragraphsRes = R.array.privacy_paragraphs,
    onDone = onDone,
  )
}
