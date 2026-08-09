package cz.kvalitacena.exception;

import lombok.Getter;

/**
 * Zakládaný obchod/zboží už zřejmě existuje (uq_store_identity / uq_product_generic_name,
 * nebo klientem předem zjištěná shoda). Klient dostane id existujícího záznamu, aby mohl
 * nabídnout "použít existující" místo pouhého "zkus jiný název" — viz GraphQlExceptionHandler.
 */
@Getter
public class DuplicateException extends AppException {
  private final Long existingId;

  public DuplicateException(ErrorCode code, Long existingId) {
    super(code);
    this.existingId = existingId;
  }
}
