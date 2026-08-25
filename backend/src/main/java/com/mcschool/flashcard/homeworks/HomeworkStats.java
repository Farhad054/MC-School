package com.mcschool.flashcard.homeworks;

import java.util.UUID;

public record HomeworkStats(
        UUID homeworkId,
        long totalCards,
        long notStarted,
        long inProgress,
        long learned
) {
}
