package com.mcschool.flashcard.users;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Creates the random tokens used in account-invitation links. */
public final class Invitations {

    /** How long an invitation link stays valid. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Invitations() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    public static Instant expiry(Instant now) {
        return now.plus(VALIDITY);
    }
}
