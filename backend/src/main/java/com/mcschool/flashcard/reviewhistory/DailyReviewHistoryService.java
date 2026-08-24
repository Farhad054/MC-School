package com.mcschool.flashcard.reviewhistory;

import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.reviewhistory.dto.DailyReviewHistoryResponse;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyReviewHistoryService {

    private final DailyReviewHistoryRepository historyRepository;
    private final UserRepository userRepository;

    public DailyReviewHistoryService(DailyReviewHistoryRepository historyRepository,
                                     UserRepository userRepository) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public void recordDueSnapshot(User student, LocalDate date, long dueCount) {
        if (dueCount <= 0 || historyRepository.findByStudentIdAndDate(student.getId(), date).isPresent()) {
            return;
        }
        historyRepository.save(DailyReviewHistory.missed(student, date, Math.toIntExact(dueCount)));
    }

    @Transactional
    public void closeIncompleteDaysBefore(LocalDate today) {
        historyRepository.findAllByDateBeforeAndStatusNot(today, DailyReviewStatus.COMPLETED)
                .forEach(DailyReviewHistory::markMissed);
    }

    @Transactional
    public void recordScheduledSessionStarted(User student, LocalDate date, int dueCount) {
        DailyReviewHistory history = historyRepository.findByStudentIdAndDate(student.getId(), date)
                .orElseGet(() -> DailyReviewHistory.missed(student, date, dueCount));
        history.markPartial(dueCount);
        historyRepository.save(history);
    }

    @Transactional
    public void recordScheduledSessionCompleted(User student, LocalDate date, int completedCount) {
        DailyReviewHistory history = historyRepository.findByStudentIdAndDate(student.getId(), date)
                .orElseGet(() -> DailyReviewHistory.missed(student, date, completedCount));
        history.markCompleted(completedCount);
        historyRepository.save(history);
    }

    @Transactional(readOnly = true)
    public List<DailyReviewHistoryResponse> listForTeacher(UUID teacherId, UUID studentId) {
        requireOwnedStudent(teacherId, studentId);
        return historyRepository.findTop14ByStudentIdOrderByDateDesc(studentId).stream()
                .map(DailyReviewHistoryResponse::from)
                .toList();
    }

    private User requireOwnedStudent(UUID teacherId, UUID studentId) {
        return userRepository.findById(studentId)
                .filter(u -> u.getRole() == Role.STUDENT)
                .filter(u -> !u.isArchived())
                .filter(u -> u.getTeacher() != null && u.getTeacher().getId().equals(teacherId))
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));
    }
}
