package cz.kvalitacena.security;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record OtpVerifyRequest(
    @NotNull UUID challengeUid,
    @NotBlank @Pattern(regexp = "\\d{6}") String code,
    @NotBlank @Email String email) {
}
