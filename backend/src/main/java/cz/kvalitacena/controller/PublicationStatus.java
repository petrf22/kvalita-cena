package cz.kvalitacena.controller;

/**
 * Jeden zdroj pravdy pro "kdy se to zveřejní" napříč všemi čtyřmi sekcemi výpisu "Moje
 * příspěvky" (docs/datovy-model.md, "Uživatelská vrstva nad globálními daty"; prahy v
 * docs/reputace.md). {@code confirmationsReceived}/{@code confirmationsRequired} jsou null
 * mimo {@link PublicationState#AWAITING_CONFIRMATIONS} — mimo tenhle stav číslo nedává smysl.
 */
public record PublicationStatus(PublicationState state, Integer confirmationsReceived,
                                  Integer confirmationsRequired, boolean verified) {

  public static PublicationStatus publicState(boolean verified) {
    return new PublicationStatus(PublicationState.PUBLIC, null, null, verified);
  }

  public static PublicationStatus hiddenAfterFlags() {
    return new PublicationStatus(PublicationState.HIDDEN_AFTER_FLAGS, null, null, false);
  }

  public static PublicationStatus awaitingConfirmations(int received, int required) {
    return new PublicationStatus(PublicationState.AWAITING_CONFIRMATIONS, received, required, false);
  }

  public static PublicationStatus pendingMerge() {
    return new PublicationStatus(PublicationState.PENDING_MERGE, null, null, false);
  }
}
