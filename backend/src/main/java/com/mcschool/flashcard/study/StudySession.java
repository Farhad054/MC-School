package com.mcschool.flashcard.study;

import com.mcschool.flashcard.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One run through a student's cards until every card has been answered correctly.
 * Persisted so a student can leave and resume, and so the completed run is kept as
 * session history. The per-card progress lives in {@link StudySessionItem} rows; the
 * relationship is intentionally one-directional (queried via the repository) to keep
 * the entity simple and avoid recursive serialization.
 */
@Entity
@Table(name = "study_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StudySession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", nullable = false, length = 20)
    private SessionType sessionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Column(name = "total_cards", nullable = false)
    private int totalCards;

    @Column(name = "correct_first_try", nullable = false)
    private int correctFirstTry;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private StudySession(User student, SessionType sessionType, int totalCards) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.sessionType = sessionType;
        this.status = SessionStatus.IN_PROGRESS;
        this.totalCards = totalCards;
        this.correctFirstTry = 0;
        this.startedAt = Instant.now();
    }

    public static StudySession start(User student, SessionType sessionType, int totalCards) {
        return new StudySession(student, sessionType, totalCards);
    }

    public void recordCorrectFirstTry() {
        this.correctFirstTry++;
    }

    public void markCompleted() {
        this.status = SessionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public boolean isCompleted() {
        return status == SessionStatus.COMPLETED;
    }

    public boolean isScheduled() {
        return sessionType == SessionType.SCHEDULED;
    }
}
