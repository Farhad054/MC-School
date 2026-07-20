package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.cards.CardRepository;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Once a day, notifies every student who has cards due for review (PRD 4.6).
 *
 * <p>Disabled by default: the bean only exists when
 * {@code app.notifications.review-reminders.enabled=true}. That keeps it off in
 * tests and in local development where {@link LoggingNotificationService} would
 * otherwise just print. Enable it once a real email provider is configured.
 */
@Component
@ConditionalOnProperty(name = "app.notifications.review-reminders.enabled", havingValue = "true")
public class ReviewReminderScheduler {

    private final UserRepository userRepository;
    private final CardRepository cardRepository;
    private final NotificationService notificationService;

    public ReviewReminderScheduler(UserRepository userRepository, CardRepository cardRepository,
                                   NotificationService notificationService) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "${app.notifications.review-reminders.cron}")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now();
        for (User student : userRepository.findAllByRoleOrderByFullNameAsc(Role.STUDENT)) {
            long dueCards = cardRepository.countDueCards(student.getId(), today);
            if (dueCards > 0) {
                notificationService.sendReviewReminder(student, dueCards);
            }
        }
    }
}
