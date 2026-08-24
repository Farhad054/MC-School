package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.users.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends real transactional email via SMTP. Active only when
 * {@code app.mail.enabled=true}; the SMTP server is configured through the
 * standard {@code spring.mail.*} properties, and the sender address through
 * {@code app.mail.from}.
 *
 * <p>Delivery failures are logged, never rethrown, so a temporary mail outage can
 * not fail account creation — the invitation still exists and can be re-sent.
 */
@Service
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class EmailNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final AppLinks appLinks;
    private final String from;

    public EmailNotificationService(JavaMailSender mailSender, AppLinks appLinks,
                                    @Value("${app.mail.from}") String from) {
        this.mailSender = mailSender;
        this.appLinks = appLinks;
        this.from = from;
    }

    @Override
    public void sendInvitation(User invitee, String invitationToken) {
        NotificationMessages.Email email = NotificationMessages.invitation(
                invitee.getPreferredLanguage(), invitee.getFullName(),
                appLinks.activationLink(invitationToken));
        send(invitee.getEmail(), email);
    }

    @Override
    public void sendReviewReminder(User student, long dueCardCount) {
        NotificationMessages.Email email = NotificationMessages.reviewReminder(
                student.getPreferredLanguage(), student.getFullName(), dueCardCount, appLinks.todayLink());
        send(student.getEmail(), email);
    }

    private void send(String to, NotificationMessages.Email email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // Never let a mail failure break the surrounding operation.
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }
}
