package com.mcschool.flashcard.reviewhistory.dto;

import java.util.UUID;

/** First answer given for one card in a completed scheduled review session. */
public record DailyReviewAnswerResponse(
        UUID cardId,
        String question,
        String selectedAnswer,
        String correctAnswer,
        boolean correct
) {
}
