package com.mcschool.flashcard.auth;

import com.mcschool.flashcard.users.Role;
import java.util.UUID;

/**
 * The authenticated caller, available in controllers via
 * {@code @AuthenticationPrincipal AuthenticatedUser}.
 */
public record AuthenticatedUser(UUID id, String email, Role role) {
}
