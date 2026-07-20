package com.mcschool.flashcard.study.dto;

/**
 * Immediate feedback after answering (PRD 4.3: "the system shows right or wrong at once").
 *
 * @param correct          whether the chosen option was right
 * @param correctAnswer    the right answer, so the UI can highlight it when the student was wrong
 * @param sessionCompleted whether that answer finished the session
 * @param remaining        cards still pending in the queue
 */
public record AnswerResultResponse(
        boolean correct,
        String correctAnswer,
        boolean sessionCompleted,
        int remaining
) {
}
