package com.mcschool.flashcard.study.dto;

import java.util.UUID;

/** One card's first-try review data shown after a completed study session. */
public record SessionReviewItemResponse(
        UUID cardId,
        String question,
        String selectedAnswer,
        String correctAnswer,
        boolean correct
) {
}
