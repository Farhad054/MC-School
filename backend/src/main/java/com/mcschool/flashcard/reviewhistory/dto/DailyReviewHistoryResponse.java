package com.mcschool.flashcard.reviewhistory.dto;

import com.mcschool.flashcard.reviewhistory.DailyReviewHistory;
import com.mcschool.flashcard.reviewhistory.DailyReviewStatus;
import java.time.LocalDate;
import java.util.List;

public record DailyReviewHistoryResponse(
        LocalDate date,
        int dueCount,
        int completedCount,
        DailyReviewStatus status,
        List<DailyReviewAnswerResponse> answers
) {

    public static DailyReviewHistoryResponse from(DailyReviewHistory history,
                                                   List<DailyReviewAnswerResponse> answers) {
        return new DailyReviewHistoryResponse(history.getDate(), history.getDueCount(),
                history.getCompletedCount(), history.getStatus(), answers);
    }
}
