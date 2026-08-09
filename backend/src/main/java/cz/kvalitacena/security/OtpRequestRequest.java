package cz.kvalitacena.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record OtpRequestRequest(
    @NotBlank(message = "{validation.email.notBlank}") @Email(message = "{validation.email.invalid}") String email) {
}
