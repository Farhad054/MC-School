package com.mcschool.flashcard.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NotificationServiceContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AppLinks.class, EmailNotificationService.class, LoggingNotificationService.class)
            .withPropertyValues(
                    "app.frontend.base-url=http://frontend.test",
                    "app.mail.from=sender@test.local",
                    "app.mail.enabled=true",
                    "app.brevo.api-key=test");

    @Test
    void mailEnabledUsesBrevoEmailNotificationService() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(NotificationService.class);
            assertThat(context).hasSingleBean(EmailNotificationService.class);
            assertThat(context).doesNotHaveBean(LoggingNotificationService.class);
        });
    }
}
