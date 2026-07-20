package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default notification implementation: it logs what would be sent instead of
 * sending real email, so the notification flow can be developed and tested before
 * an email provider is wired in. Replace with a provider-backed implementation to
 * actually deliver messages.
 */
@Service
public class LoggingNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationService.class);

    @Override
    public void sendReviewReminder(User student, long dueCardCount) {
        // Never log secrets; email is contact data the teacher already entered.
        log.info("[notification] Review reminder for {} — {} card(s) due today "
                + "(email transport not configured yet)", student.getEmail(), dueCardCount);
    }
}
