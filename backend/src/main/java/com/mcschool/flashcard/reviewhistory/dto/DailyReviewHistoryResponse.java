package com.mcschool.flashcard.reviewhistory.dto;

import com.mcschool.flashcard.reviewhistory.DailyReviewHistory;
import com.mcschool.flashcard.reviewhistory.DailyReviewStatus;
import java.time.LocalDate;

public record DailyReviewHistoryResponse(
        LocalDate date,
        int dueCount,
        int completedCount,
        DailyReviewStatus status
) {

    public static DailyReviewHistoryResponse from(DailyReviewHistory history) {
        return new DailyReviewHistoryResponse(history.getDate(), history.getDueCount(),
                history.getCompletedCount(), history.getStatus());
    }
}
