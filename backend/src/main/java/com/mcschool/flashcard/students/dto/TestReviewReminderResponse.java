package com.mcschool.flashcard.students.dto;

import java.util.UUID;

public record TestReviewReminderResponse(
        UUID studentId,
        long dueCount,
        boolean reminderAttempted
) {
}
