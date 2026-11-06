package com.mcschool.flashcard.study.dto;

import com.mcschool.flashcard.study.SessionType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Which kind of session to start: {@code SCHEDULED} (today's due homework, advances
 * the schedule) or {@code PRACTICE} (voluntary, leaves the schedule untouched).
 */
public record StartSessionRequest(
        @NotNull SessionType type,
        UUID homeworkId
) {
}
