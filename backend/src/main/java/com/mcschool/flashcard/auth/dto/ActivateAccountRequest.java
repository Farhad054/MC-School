package com.mcschool.flashcard.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActivateAccountRequest(
        @NotBlank String invitationToken,
        // 72 bytes is the BCrypt input limit.
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
