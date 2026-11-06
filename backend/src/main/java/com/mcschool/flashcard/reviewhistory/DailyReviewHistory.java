package com.mcschool.flashcard.reviewhistory;

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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Stored daily review snapshot for teacher-facing history. Counts are persisted
 * for a calendar day and are not recalculated later from current card state.
 */
@Entity
@Table(name = "daily_review_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReviewHistory {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @Column(name = "review_date", nullable = false)
    private LocalDate date;

    @Column(name = "due_count", nullable = false)
    private int dueCount;

    @Column(name = "completed_count", nullable = false)
    private int completedCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DailyReviewStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private DailyReviewHistory(User student, LocalDate date, int dueCount, int completedCount,
                               DailyReviewStatus status) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.date = date;
        this.dueCount = dueCount;
        this.completedCount = completedCount;
        this.status = status;
    }

    public static DailyReviewHistory missed(User student, LocalDate date, int dueCount) {
        return new DailyReviewHistory(student, date, dueCount, 0, DailyReviewStatus.MISSED);
    }

    public void markPartial(int dueCount) {
        this.dueCount = Math.max(this.dueCount, dueCount);
        this.status = DailyReviewStatus.PARTIAL;
    }

    public void markCompleted(int completedCount) {
        this.dueCount = Math.max(this.dueCount, completedCount);
        this.completedCount = completedCount;
        this.status = DailyReviewStatus.COMPLETED;
    }

    public void markMissed() {
        if (this.status != DailyReviewStatus.COMPLETED) {
            this.status = DailyReviewStatus.MISSED;
        }
    }
}
