package com.mcschool.flashcard.cards.dto;

/**
 * Status breakdown of a student's cards, shown to the teacher on the student page
 * (PRD 4.2: "how many active, how many learned, how many awaiting repetition").
 *
 * @param total              all cards the student has
 * @param dueNow             ACTIVE cards that can be studied right now
 * @param awaitingRepetition ACTIVE cards whose next review is in the future
 * @param learned            cards that have been fully learned
 */
public record CardSummaryResponse(
        long total,
        long dueNow,
        long awaitingRepetition,
        long learned
) {
}
