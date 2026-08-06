package cz.kvalitacena.controller;

/** Výsledek {@code flagRecord} — kolik různých lidí záznam nahlásilo a jestli je teď skrytý. */
public record FlagResult(long flagCount, boolean hidden) {
}
