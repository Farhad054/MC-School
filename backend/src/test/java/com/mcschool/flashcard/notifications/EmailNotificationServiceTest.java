package com.mcschool.flashcard.notifications;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mcschool.flashcard.users.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailNotificationServiceTest {

    @Test
    void invitationMailFailureIsLoggedAndNotRethrown() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("SMTP timeout"))
                .when(mailSender).send(any(SimpleMailMessage.class));
        EmailNotificationService service = new EmailNotificationService(
                mailSender, new AppLinks("http://frontend.test"), "no-reply@test.local");
        User teacher = User.invitedTeacher("Teacher", "teacher@test.local",
                "teacher-token", Instant.now().plusSeconds(3600));
        User student = User.invitedStudent("Student", "student@test.local", teacher,
                "student-token", Instant.now().plusSeconds(3600));

        assertThatNoException().isThrownBy(() -> service.sendInvitation(student, "student-token"));

        verify(mailSender).send(any(SimpleMailMessage.class));
    }
}
