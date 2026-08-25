package com.mcschool.flashcard.homeworks.dto;

import com.mcschool.flashcard.homeworks.Homework;
import com.mcschool.flashcard.homeworks.HomeworkStats;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record HomeworkResponse(
        UUID id,
        UUID studentId,
        LocalDate startDate,
        Instant createdAt,
        long totalCards,
        long notStarted,
        long inProgress,
        long learned
) {
    public static HomeworkResponse from(Homework homework, Map<UUID, HomeworkStats> statsByHomework) {
        HomeworkStats stats = statsByHomework.getOrDefault(homework.getId(),
                new HomeworkStats(homework.getId(), 0, 0, 0, 0));
        return new HomeworkResponse(homework.getId(), homework.getStudent().getId(),
                homework.getStartDate(), homework.getCreatedAt(), stats.totalCards(),
                stats.notStarted(), stats.inProgress(), stats.learned());
    }
}
