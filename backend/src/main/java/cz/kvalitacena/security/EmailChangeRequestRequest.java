package cz.kvalitacena.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Nová přihlašovací adresa — kód jde na TUHLE adresu, ne na tu současnou. */
public record EmailChangeRequestRequest(
    @NotBlank(message = "{validation.email.notBlank}") @Email(message = "{validation.email.invalid}") String email) {
}
