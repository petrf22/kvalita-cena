package cz.kvalitacena.db.entity;

/** Určuje se leave-one-out (viz docs/reputace.md) — v etapě 1 zatím vždy PENDING. */
public enum AgreementStatus {
  PENDING,
  AGREE,
  DISAGREE,
  NEUTRAL
}
