package com.mcschool.flashcard.reviewhistory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DailyReviewHistoryServiceTest {

    private final DailyReviewHistoryRepository historyRepository = mock(DailyReviewHistoryRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final DailyReviewHistoryService service =
            new DailyReviewHistoryService(historyRepository, userRepository);

    private final User teacher = User.invitedTeacher("Teacher", "teacher@test.local",
            "teacher-token", Instant.now().plusSeconds(3600));
    private final User student = User.invitedStudent("Student", "student@test.local", teacher,
            "student-token", Instant.now().plusSeconds(3600));
    private final LocalDate today = LocalDate.of(2026, 8, 24);

    @Test
    void dueSnapshotStoresMissedRowOnlyWhenCardsAreDue() {
        when(historyRepository.findByStudentIdAndDate(student.getId(), today)).thenReturn(Optional.empty());

        service.recordDueSnapshot(student, today, 8);

        ArgumentCaptor<DailyReviewHistory> captor = ArgumentCaptor.forClass(DailyReviewHistory.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getStudent()).isEqualTo(student);
        assertThat(captor.getValue().getDate()).isEqualTo(today);
        assertThat(captor.getValue().getDueCount()).isEqualTo(8);
        assertThat(captor.getValue().getCompletedCount()).isZero();
        assertThat(captor.getValue().getStatus()).isEqualTo(DailyReviewStatus.MISSED);
    }

    @Test
    void dueSnapshotSkipsZeroDueCards() {
        service.recordDueSnapshot(student, today, 0);

        verify(historyRepository, never()).save(any());
    }

    @Test
    void scheduledSessionStartMarksDayPartial() {
        DailyReviewHistory history = DailyReviewHistory.missed(student, today, 8);
        when(historyRepository.findByStudentIdAndDate(student.getId(), today)).thenReturn(Optional.of(history));

        service.recordScheduledSessionStarted(student, today, 8);

        assertThat(history.getCompletedCount()).isZero();
        assertThat(history.getStatus()).isEqualTo(DailyReviewStatus.PARTIAL);
        verify(historyRepository).save(history);
    }

    @Test
    void scheduledSessionCompletionMarksDayCompleted() {
        DailyReviewHistory history = DailyReviewHistory.missed(student, today, 8);
        history.markPartial(8);
        when(historyRepository.findByStudentIdAndDate(student.getId(), today)).thenReturn(Optional.of(history));

        service.recordScheduledSessionCompleted(student, today, 8);

        assertThat(history.getDueCount()).isEqualTo(8);
        assertThat(history.getCompletedCount()).isEqualTo(8);
        assertThat(history.getStatus()).isEqualTo(DailyReviewStatus.COMPLETED);
        verify(historyRepository).save(history);
    }

    @Test
    void closesPreviousIncompleteDaysAsMissed() {
        LocalDate yesterday = today.minusDays(1);
        DailyReviewHistory history = DailyReviewHistory.missed(student, yesterday, 8);
        history.markPartial(8);
        when(historyRepository.findAllByDateBeforeAndStatusNot(today, DailyReviewStatus.COMPLETED))
                .thenReturn(List.of(history));

        service.closeIncompleteDaysBefore(today);

        assertThat(history.getStatus()).isEqualTo(DailyReviewStatus.MISSED);
    }

    @Test
    void completedHistoricalDayDoesNotBecomeMissed() {
        LocalDate yesterday = today.minusDays(1);
        DailyReviewHistory history = DailyReviewHistory.missed(student, yesterday, 8);
        history.markCompleted(8);
        when(historyRepository.findAllByDateBeforeAndStatusNot(today, DailyReviewStatus.COMPLETED))
                .thenReturn(List.of(history));

        service.closeIncompleteDaysBefore(today);

        assertThat(history.getStatus()).isEqualTo(DailyReviewStatus.COMPLETED);
        assertThat(history.getCompletedCount()).isEqualTo(8);
    }

    @Test
    void teacherHistoryReadsStoredRowsForOwnedStudent() {
        DailyReviewHistory history = DailyReviewHistory.missed(student, today, 6);
        when(userRepository.findById(student.getId())).thenReturn(Optional.of(student));
        when(historyRepository.findTop14ByStudentIdOrderByDateDesc(student.getId()))
                .thenReturn(List.of(history));

        var rows = service.listForTeacher(teacher.getId(), student.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).date()).isEqualTo(today);
        assertThat(rows.get(0).dueCount()).isEqualTo(6);
        assertThat(rows.get(0).status()).isEqualTo(DailyReviewStatus.MISSED);
    }
}
