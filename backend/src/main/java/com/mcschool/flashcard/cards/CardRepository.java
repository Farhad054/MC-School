package com.mcschool.flashcard.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * All read queries exclude archived (soft-deleted) cards, so an archived card
 * disappears from every list, summary, study session, and distractor pool while
 * its row is kept for session history.
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findAllByStudentIdAndArchivedFalseOrderByCreatedAtDesc(UUID studentId);

    List<Card> findAllByHomeworkIdAndArchivedFalseOrderByCreatedAtDesc(UUID homeworkId);

    List<Card> findAllByHomeworkIdAndStudentIdAndArchivedFalseOrderByCreatedAtDesc(UUID homeworkId, UUID studentId);

    long countByStudentIdAndArchivedFalse(UUID studentId);

    /** Cards that are due for a scheduled session on the given day, oldest due first. */
    @Query("""
            SELECT c FROM Card c
            WHERE c.student.id = :studentId
              AND c.archived = false
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
              AND (c.repetitionNumber > 0 OR c.homework.startDate <= :day)
            ORDER BY c.dueDate ASC, c.createdAt ASC
            """)
    List<Card> findDueCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    @Query("""
            SELECT COUNT(c) FROM Card c
            WHERE c.student.id = :studentId
              AND c.archived = false
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
              AND (c.repetitionNumber > 0 OR c.homework.startDate <= :day)
            """)
    long countDueCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    @Query("""
            SELECT c FROM Card c
            WHERE c.student.id = :studentId
              AND c.archived = false
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
              AND (c.repetitionNumber > 0 OR c.homework.startDate <= :day)
            ORDER BY c.createdAt DESC
            """)
    List<Card> findAvailableStudyCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    @Query("""
            SELECT COUNT(c) FROM Card c
            WHERE c.student.id = :studentId
              AND c.archived = false
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
              AND (c.repetitionNumber > 0 OR c.homework.startDate <= :day)
            """)
    long countAvailableStudyCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    /** A student's non-archived cards (any status) — used to build the distractor pool. */
    List<Card> findAllByStudentIdAndArchivedFalse(UUID studentId);

    Optional<Card> findFirstByStudentIdAndStatusAndArchivedFalseOrderByCreatedAtAsc(
            UUID studentId, CardStatus status);

    long countByStudentIdAndStatusAndArchivedFalse(UUID studentId, CardStatus status);

    @Modifying
    @Query("UPDATE Card c SET c.archived = true WHERE c.student.id = :studentId AND c.archived = false")
    int archiveAllByStudentId(@Param("studentId") UUID studentId);

    /**
     * Number of ACTIVE cards that are not yet due (awaiting their next review).
     * "Awaiting repetition" count on the teacher's student overview.
     */
    @Query("""
            SELECT COUNT(c) FROM Card c
            WHERE c.student.id = :studentId
              AND c.archived = false
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate > :day
            """)
    long countAwaitingRepetition(@Param("studentId") UUID studentId, @Param("day") LocalDate day);
}
