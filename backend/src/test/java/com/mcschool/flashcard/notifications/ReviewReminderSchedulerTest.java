package com.mcschool.flashcard.notifications;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.cards.CardRepository;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewReminderSchedulerTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    @Test
    void sendsRemindersOnlyForActiveNonArchivedStudentsUsingConfiguredZoneDate() {
        ReviewReminderScheduler scheduler = new ReviewReminderScheduler(
                userRepository, cardRepository, notificationService, "Europe/Berlin");
        User teacher = User.invitedTeacher("Teacher", "teacher@test.local",
                "teacher-token", Instant.now().plusSeconds(3600));
        User student = User.invitedStudent("Student", "student@test.local", teacher,
                "student-token", Instant.now().plusSeconds(3600));
        student.activate("hash");
        LocalDate berlinToday = LocalDate.now(ZoneId.of("Europe/Berlin"));

        when(userRepository.findAllByRoleAndStatusAndArchivedFalseOrderByFullNameAsc(
                Role.STUDENT, UserStatus.ACTIVE)).thenReturn(List.of(student));
        when(cardRepository.countDueCards(student.getId(), berlinToday)).thenReturn(3L);

        scheduler.sendDueReminders();

        verify(userRepository).findAllByRoleAndStatusAndArchivedFalseOrderByFullNameAsc(
                Role.STUDENT, UserStatus.ACTIVE);
        verify(cardRepository).countDueCards(student.getId(), berlinToday);
        verify(notificationService).sendReviewReminder(student, 3L);
    }
}
