package com.mcschool.flashcard.auth.dto;

import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;

public record AuthResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        UserResponse user
) {
    public static AuthResponse bearer(String accessToken, Instant expiresAt, UserResponse user) {
        return new AuthResponse(accessToken, "Bearer", expiresAt, user);
    }
}
