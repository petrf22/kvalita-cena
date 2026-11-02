package cz.kvalitacena.db.entity;

/** Odkud je čitelný název řádku účtenky (tools/uctenky/pretty.py, ai_pretty.py). */
public enum LabelDisplaySource {
  /** Ruční přepis celého označení ve slovníku provozovatele (slovnik/nazvy.tsv). */
  DICT,
  /** Pravidla a slovník zkratek — deterministicky. */
  RULES,
  /** Dočištěno lokálním modelem pod deterministickou kontrolou (ai_pretty.accept). */
  AI
}
