package com.mcschool.flashcard.study;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudySessionItemRepository extends JpaRepository<StudySessionItem, UUID> {

    List<StudySessionItem> findAllBySessionId(UUID sessionId);

    /** The next card to show: the pending item with the smallest queue position. */
    Optional<StudySessionItem> findFirstBySessionIdAndStateOrderByQueuePositionAsc(
            UUID sessionId, ItemState state);

    Optional<StudySessionItem> findBySessionIdAndCardId(UUID sessionId, UUID cardId);

    long countBySessionIdAndState(UUID sessionId, ItemState state);

    /** Highest queue position in the session, used to move a wrong card to the back. */
    @Query("SELECT MAX(i.queuePosition) FROM StudySessionItem i WHERE i.session.id = :sessionId")
    Integer findMaxQueuePosition(@Param("sessionId") UUID sessionId);
}
