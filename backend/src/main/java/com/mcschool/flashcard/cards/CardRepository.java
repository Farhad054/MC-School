package com.mcschool.flashcard.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardRepository extends JpaRepository<Card, UUID> {

    List<Card> findAllByStudentIdOrderByCreatedAtDesc(UUID studentId);

    long countByStudentId(UUID studentId);

    /** Cards that are due for a scheduled session on the given day, oldest due first. */
    @Query("""
            SELECT c FROM Card c
            WHERE c.student.id = :studentId
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
            ORDER BY c.dueDate ASC, c.createdAt ASC
            """)
    List<Card> findDueCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    @Query("""
            SELECT COUNT(c) FROM Card c
            WHERE c.student.id = :studentId
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate <= :day
            """)
    long countDueCards(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    /** All of a student's cards (any status) — used to build the distractor pool. */
    List<Card> findAllByStudentId(UUID studentId);

    long countByStudentIdAndStatus(UUID studentId, CardStatus status);

    /**
     * Number of ACTIVE cards that are not yet due (awaiting their next review).
     * "Awaiting repetition" count on the teacher's student overview.
     */
    @Query("""
            SELECT COUNT(c) FROM Card c
            WHERE c.student.id = :studentId
              AND c.status = com.mcschool.flashcard.cards.CardStatus.ACTIVE
              AND c.dueDate > :day
            """)
    long countAwaitingRepetition(@Param("studentId") UUID studentId, @Param("day") LocalDate day);

    Optional<Card> findByIdAndStudentId(UUID id, UUID studentId);
}
