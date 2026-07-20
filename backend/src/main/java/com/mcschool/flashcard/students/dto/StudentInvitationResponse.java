package com.mcschool.flashcard.students.dto;

import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;

/**
 * Returned once, when a student account is created. Until invitation emails
 * are implemented, the teacher shares the token with the student manually.
 */
public record StudentInvitationResponse(
        UserResponse student,
        String invitationToken,
        Instant invitationExpiresAt
) {
}
