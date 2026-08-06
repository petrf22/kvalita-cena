package cz.kvalitacena.exception;

import lombok.Getter;

/**
 * Zakládaný obchod/zboží už zřejmě existuje (uq_store_identity / uq_product_generic_name,
 * nebo klientem předem zjištěná shoda). Klient dostane id existujícího záznamu, aby mohl
 * nabídnout "použít existující" místo pouhého "zkus jiný název" — viz GraphQlExceptionHandler.
 */
@Getter
public class DuplicateException extends RuntimeException {
  private final Long existingId;

  public DuplicateException(String message, Long existingId) {
    super(message);
    this.existingId = existingId;
  }
}
