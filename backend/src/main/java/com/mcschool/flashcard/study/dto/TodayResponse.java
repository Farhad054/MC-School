package com.mcschool.flashcard.study.dto;

import java.util.UUID;

/**
 * The student's home screen (PRD: "today's tasks"). Tells the UI whether a
 * scheduled session or voluntary practice can be started right now, and surfaces
 * any session already in progress so it can be resumed.
 *
 * @param dueCardCount        cards due for today's mandatory session
 * @param minCardsToStart     minimum cards required before any session can start (4)
 * @param canStartScheduled   there are due cards and enough cards to build options
 * @param canPractice         enough cards to run a voluntary practice session
 * @param inProgressSessionId an unfinished session to resume, or {@code null}
 */
public record TodayResponse(
        long totalCards,
        long dueCardCount,
        long learnedCount,
        int minCardsToStart,
        boolean canStartScheduled,
        boolean canPractice,
        UUID inProgressSessionId
) {
}
