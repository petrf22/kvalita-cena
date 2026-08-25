package cz.kvalitacena.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record AccountDeleteConfirmRequest(
    @NotNull(message = "{validation.challengeUid.required}") UUID challengeUid,
    @NotBlank(message = "{validation.code.notBlank}")
    @Pattern(regexp = "\\d{6}", message = "{validation.code.invalid}") String code) {
}
