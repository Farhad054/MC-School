package com.mcschool.flashcard.reviewhistory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyReviewHistoryRepository extends JpaRepository<DailyReviewHistory, UUID> {

    Optional<DailyReviewHistory> findByStudentIdAndDate(UUID studentId, LocalDate date);

    List<DailyReviewHistory> findTop14ByStudentIdOrderByDateDesc(UUID studentId);

    List<DailyReviewHistory> findAllByDateBeforeAndStatusNot(LocalDate date, DailyReviewStatus status);
}
