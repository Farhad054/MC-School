package com.mcschool.flashcard.study.dto;

import com.mcschool.flashcard.study.SessionType;
import java.time.LocalDate;
import java.util.List;

/**
 * The result screen shown after a session completes (PRD 4.3): how many cards were
 * correct on the first try, and when the next review is due.
 *
 * @param nextReviewDate soonest upcoming review across the session's cards; {@code null}
 *                       if every card in the session is now learned
 */
public record SessionResultResponse(
        SessionType type,
        int totalCards,
        int correctFirstTry,
        LocalDate nextReviewDate,
        List<SessionReviewItemResponse> review
) {
}
