package com.mcschool.flashcard.notifications;

import com.mcschool.flashcard.users.Language;

/**
 * Subject/body text for notification emails, in the recipient's language
 * (German or Russian, per PRD 6). Plain text keeps delivery robust; HTML
 * templates can replace this later without touching the send logic.
 */
final class NotificationMessages {

    record Email(String subject, String body) {
    }

    private NotificationMessages() {
    }

    static Email invitation(Language language, String fullName, String activationLink) {
        if (language == Language.DE) {
            return new Email(
                    "Willkommen bei Mindcraft School",
                    "Hallo " + fullName + ",\n\n"
                            + "es wurde ein Konto für dich erstellt. Öffne den folgenden Link, "
                            + "um dein Passwort festzulegen und dich anzumelden:\n\n"
                            + activationLink + "\n\n"
                            + "Der Link ist 7 Tage gültig.\n\n"
                            + "Mindcraft School");
        }
        return new Email(
                "Добро пожаловать в Mindcraft School",
                "Здравствуйте, " + fullName + "!\n\n"
                        + "Для вас создан аккаунт. Перейдите по ссылке ниже, чтобы задать "
                        + "пароль и войти:\n\n"
                        + activationLink + "\n\n"
                        + "Ссылка действительна 7 дней.\n\n"
                        + "Mindcraft School");
    }

    static Email reviewReminder(Language language, String fullName, long dueCardCount, String loginLink) {
        if (language == Language.DE) {
            return new Email(
                    "Heute sind Karten zur Wiederholung fällig",
                    "Hallo " + fullName + ",\n\n"
                            + "du hast heute " + dueCardCount + " Karte(n) zu wiederholen. "
                            + "Melde dich an, um sie zu bearbeiten:\n\n"
                            + loginLink + "\n\n"
                            + "Mindcraft School");
        }
        return new Email(
                "Сегодня есть карточки для повторения",
                "Здравствуйте, " + fullName + "!\n\n"
                        + "Сегодня нужно повторить " + dueCardCount + " карточк(и). "
                        + "Войдите, чтобы начать:\n\n"
                        + loginLink + "\n\n"
                        + "Mindcraft School");
    }
}
