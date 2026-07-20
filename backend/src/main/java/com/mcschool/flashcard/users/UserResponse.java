package com.mcschool.flashcard.users;

import java.util.UUID;

/** Public representation of an account. Never exposes the password hash or invitation token. */
public record UserResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        UserStatus status,
        Language preferredLanguage
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(),
                user.getRole(), user.getStatus(), user.getPreferredLanguage());
    }
}
