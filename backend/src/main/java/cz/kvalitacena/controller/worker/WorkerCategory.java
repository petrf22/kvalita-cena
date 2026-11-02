package cz.kvalitacena.controller.worker;

/**
 * Kategorie appky pro návrh modelu (tools/uctenky/ai_classify.py) — slug, který model vrací,
 * a celá cesta česky („Potraviny › Mléčné výrobky › Mléko"), aby se model měl podle čeho
 * rozhodnout. Jen veřejná data katalogu.
 */
public record WorkerCategory(String slug, String path) {
}
