package cz.kvalitacena.security;

import cz.kvalitacena.db.entity.AccountDeleteMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/** {@code mode} se rozhoduje až tady, ne u requestu — request jen ověřuje vlastnictví schránky. */
public record AccountDeleteConfirmRequest(
    @NotNull(message = "{validation.challengeUid.required}") UUID challengeUid,
    @NotBlank(message = "{validation.code.notBlank}")
    @Pattern(regexp = "\\d{6}", message = "{validation.code.invalid}") String code,
    @NotNull(message = "{validation.accountDeleteMode.required}") AccountDeleteMode mode) {
}
