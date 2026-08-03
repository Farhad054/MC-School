package com.mcschool.flashcard.cards;

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
 * A single flashcard: one question and its correct answer, owned by one student
 * and created by that student's teacher. The spaced-repetition state
 * ({@link #repetitionNumber}, {@link #dueDate}, {@link #status}) is advanced by
 * {@link com.mcschool.flashcard.study.Sm2Scheduler} and applied through
 * {@link #applyScheduling}.
 */
@Entity
@Table(name = "cards")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Card {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private User student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_teacher_id", nullable = false)
    private User createdByTeacher;

    @Column(nullable = false, length = 1000)
    private String question;

    @Column(name = "correct_answer", nullable = false, length = 500)
    private String correctAnswer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status;

    @Column(name = "repetition_number", nullable = false)
    private int repetitionNumber;

    /** Next day this card must be reviewed; {@code null} once the card is LEARNED. */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Soft-delete flag. An archived card is hidden from all lists and study, but the
     * row is kept so study-session history that references it stays intact.
     */
    @Column(nullable = false)
    private boolean archived;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    private Card(User student, User createdByTeacher, String question, String correctAnswer) {
        this.id = UUID.randomUUID();
        this.student = student;
        this.createdByTeacher = createdByTeacher;
        this.question = question;
        this.correctAnswer = correctAnswer;
        this.status = CardStatus.ACTIVE;
        this.repetitionNumber = 0;
        // Newly created cards are due immediately so they appear in the next session.
        this.dueDate = LocalDate.now();
    }

    public static Card create(User student, User createdByTeacher, String question, String correctAnswer) {
        return new Card(student, createdByTeacher, question.strip(), correctAnswer.strip());
    }

    /** Teacher edits the question and/or answer; the review schedule is left untouched. */
    public void edit(String question, String correctAnswer) {
        this.question = question.strip();
        this.correctAnswer = correctAnswer.strip();
    }

    /** Soft-deletes the card: it disappears from lists and study but the row is kept. */
    public void archive() {
        this.archived = true;
    }

    /** Applies a new spaced-repetition state after a successful scheduled review. */
    public void applyScheduling(int repetitionNumber, LocalDate dueDate, CardStatus status) {
        this.repetitionNumber = repetitionNumber;
        this.dueDate = dueDate;
        this.status = status;
    }

    public boolean isDueOn(LocalDate day) {
        return status == CardStatus.ACTIVE && dueDate != null && !dueDate.isAfter(day);
    }
}
