package cz.kvalitacena.ui.legal

import androidx.compose.runtime.Composable
import cz.kvalitacena.R

/** Podmínky užití — zrcadlí docs/podminky-uziti.md, viz [LegalScreen]. */
@Composable
fun TermsScreen(onDone: () -> Unit) {
  LegalScreen(
    titleRes = R.string.terms_title,
    headingsRes = R.array.terms_headings,
    paragraphsRes = R.array.terms_paragraphs,
    onDone = onDone,
  )
}
