package cz.kvalitacena.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * @param termsAccepted Vyžaduje se jen při JIT registraci nového účtu (viz {@code
 *                       OtpService.verifyOtp}) — existující účet se přihlašuje, ne registruje,
 *                       takže se souhlas nevyžaduje znovu. {@code null}/{@code false} u nové
 *                       registrace request zamítne, ať appka nedokáže souhlas obejít přímým
 *                       voláním API mimo checkbox v UI.
 */
public record OtpVerifyRequest(
    @NotNull(message = "{validation.challengeUid.required}") UUID challengeUid,
    @NotBlank(message = "{validation.code.notBlank}")
    @Pattern(regexp = "\\d{6}", message = "{validation.code.invalid}") String code,
    @NotBlank(message = "{validation.email.notBlank}") @Email(message = "{validation.email.invalid}") String email,
    Boolean termsAccepted) {
}
