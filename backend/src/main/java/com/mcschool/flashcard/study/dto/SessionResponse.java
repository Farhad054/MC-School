package com.mcschool.flashcard.study.dto;

import com.mcschool.flashcard.study.SessionStatus;
import com.mcschool.flashcard.study.SessionType;
import com.mcschool.flashcard.study.StudySession;
import java.util.UUID;

/**
 * Snapshot of a session, used to start and to resume. {@code answeredCount} and
 * {@code totalCards} drive the progress bar ("6 of 43").
 */
public record SessionResponse(
        UUID id,
        SessionType type,
        SessionStatus status,
        int totalCards,
        int answeredCount,
        int remaining
) {
    public static SessionResponse of(StudySession session, int answeredCount) {
        return new SessionResponse(session.getId(), session.getSessionType(), session.getStatus(),
                session.getTotalCards(), answeredCount, session.getTotalCards() - answeredCount);
    }
}
