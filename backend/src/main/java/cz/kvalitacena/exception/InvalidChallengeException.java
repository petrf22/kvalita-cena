package cz.kvalitacena.exception;

/**
 * Neplatný, prošlý nebo vyčerpaný OTP kód/výzva. Zpráva je záměrně obecná — nerozlišuje mezi
 * "špatný kód" a "výzva neexistuje", aby nešlo postupně zjišťovat, které challengeUid jsou platné.
 */
public class InvalidChallengeException extends AppException {
  public InvalidChallengeException() {
    super(ErrorCode.INVALID_CHALLENGE);
  }
}
