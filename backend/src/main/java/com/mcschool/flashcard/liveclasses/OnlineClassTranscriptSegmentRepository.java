package com.mcschool.flashcard.liveclasses;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnlineClassTranscriptSegmentRepository
        extends JpaRepository<OnlineClassTranscriptSegment, UUID> {

    /** Ingestion idempotency: a resent final collapses onto the existing row. */
    Optional<OnlineClassTranscriptSegment> findByOnlineClassIdAndProviderSegmentId(
            UUID classId, String providerSegmentId);

    List<OnlineClassTranscriptSegment> findAllByOnlineClassIdOrderByStartMsAscIdAsc(UUID classId);

    /** Bounded current-room context; eager speakers avoid lazy reads during the provider call. */
    @EntityGraph(attributePaths = "user")
    List<OnlineClassTranscriptSegment> findByOnlineClassIdOrderByStartMsDescIdDesc(UUID classId, Pageable page);

    void deleteAllByOnlineClassId(UUID classId);
}
