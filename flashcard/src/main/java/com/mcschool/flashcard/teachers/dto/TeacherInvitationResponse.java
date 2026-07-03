package com.mcschool.flashcard.teachers.dto;

import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;

/**
 * Returned once, when a teacher account is created. Until invitation emails
 * are implemented, the admin shares the token with the teacher manually.
 */
public record TeacherInvitationResponse(
        UserResponse teacher,
        String invitationToken,
        Instant invitationExpiresAt
) {
}
