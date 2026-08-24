package com.mcschool.flashcard.notifications;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds absolute frontend URLs for use in emails. The base URL is configured via
 * {@code app.frontend.base-url} so links point at the deployed web app.
 */
@Component
public class AppLinks {

    private final String frontendBaseUrl;

    public AppLinks(@Value("${app.frontend.base-url}") String frontendBaseUrl) {
        // Trim a trailing slash so we can safely append paths.
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    /** The page where an invitee sets their password. */
    public String activationLink(String invitationToken) {
        return frontendBaseUrl + "/activate?token="
                + URLEncoder.encode(invitationToken, StandardCharsets.UTF_8);
    }

    /** The student's today page for due review work. */
    public String todayLink() {
        return frontendBaseUrl + "/today";
    }
}
