package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.cards.CardRepository;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
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
    private final ZoneId reviewRemindersZone;

    public ReviewReminderScheduler(UserRepository userRepository, CardRepository cardRepository,
                                   NotificationService notificationService,
                                   @Value("${app.notifications.review-reminders.zone}") String reviewRemindersZone) {
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
        this.notificationService = notificationService;
        this.reviewRemindersZone = ZoneId.of(reviewRemindersZone);
    }

    @Scheduled(
            cron = "${app.notifications.review-reminders.cron}",
            zone = "${app.notifications.review-reminders.zone}")
    public void sendDueReminders() {
        LocalDate today = LocalDate.now(reviewRemindersZone);
        for (User student : userRepository.findAllByRoleAndStatusAndArchivedFalseOrderByFullNameAsc(
                Role.STUDENT, UserStatus.ACTIVE)) {
            long dueCards = cardRepository.countDueCards(student.getId(), today);
            if (dueCards > 0) {
                notificationService.sendReviewReminder(student, dueCards);
            }
        }
    }
}
