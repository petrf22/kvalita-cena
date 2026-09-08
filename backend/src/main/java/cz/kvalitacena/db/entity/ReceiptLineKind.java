package cz.kvalitacena.db.entity;

/** Co řádek účtenky je. Slevu tiskne Albert jako vlastní řádek pod položkou, které se týká. */
public enum ReceiptLineKind {
  ITEM,
  DISCOUNT,
  /** Řádek, kterému parser nerozuměl — nese jen text, aby se ztráta nedala přehlédnout. */
  UNKNOWN
}
