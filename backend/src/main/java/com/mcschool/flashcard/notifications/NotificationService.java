package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.users.User;

/**
 * Sends transactional notifications to users. The MVP has a single logging
 * implementation ({@link LoggingNotificationService}); a real transactional-email
 * provider (SendGrid / Mailgun, per PRD 6) can be added by providing another
 * implementation of this interface — nothing else needs to change.
 */
public interface NotificationService {

    /** A student's spaced-repetition review is due today (PRD 4.6). */
    void sendReviewReminder(User student, long dueCardCount);
}
