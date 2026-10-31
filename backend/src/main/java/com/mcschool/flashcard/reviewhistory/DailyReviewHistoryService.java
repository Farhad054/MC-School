package com.mcschool.flashcard.reviewhistory;

import com.mcschool.flashcard.cards.Card;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.reviewhistory.dto.DailyReviewAnswerResponse;
import com.mcschool.flashcard.reviewhistory.dto.DailyReviewHistoryResponse;
import com.mcschool.flashcard.study.SessionStatus;
import com.mcschool.flashcard.study.SessionType;
import com.mcschool.flashcard.study.StudySession;
import com.mcschool.flashcard.study.StudySessionItem;
import com.mcschool.flashcard.study.StudySessionItemRepository;
import com.mcschool.flashcard.study.StudySessionRepository;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyReviewHistoryService {

    private final DailyReviewHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final StudySessionRepository sessionRepository;
    private final StudySessionItemRepository itemRepository;
    private final ZoneId reviewZone;

    public DailyReviewHistoryService(DailyReviewHistoryRepository historyRepository,
                                     UserRepository userRepository,
                                     StudySessionRepository sessionRepository,
                                     StudySessionItemRepository itemRepository,
                                     @Value("${app.notifications.review-reminders.zone}") String reviewZone) {
        this.historyRepository = historyRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.itemRepository = itemRepository;
        this.reviewZone = ZoneId.of(reviewZone);
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
        List<StudySession> sessions = sessionRepository
                .findAllByStudentIdAndStatusAndSessionTypeOrderByCompletedAtDesc(
                        studentId, SessionStatus.COMPLETED, SessionType.SCHEDULED);

        return historyRepository.findTop14ByStudentIdOrderByDateDesc(studentId).stream()
                .map(history -> DailyReviewHistoryResponse.from(history, answersForDate(history.getDate(), sessions)))
                .toList();
    }

    private List<DailyReviewAnswerResponse> answersForDate(LocalDate date, List<StudySession> sessions) {
        return sessions.stream()
                .filter(session -> session.getCompletedAt() != null)
                .filter(session -> session.getCompletedAt().atZone(reviewZone).toLocalDate().equals(date))
                .findFirst()
                .map(this::answersForSession)
                .orElseGet(List::of);
    }

    private List<DailyReviewAnswerResponse> answersForSession(StudySession session) {
        return itemRepository.findAllBySessionId(session.getId()).stream()
                .sorted(Comparator.comparing(StudySessionItem::getCreatedAt)
                        .thenComparing(StudySessionItem::getId))
                .map(item -> {
                    Card card = item.getCard();
                    String selectedAnswer = item.getFirstSelectedAnswer();
                    boolean correct = item.isFirstTryClean();
                    return new DailyReviewAnswerResponse(card.getId(), card.getQuestion(), selectedAnswer,
                            card.getCorrectAnswer(), correct);
                })
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
