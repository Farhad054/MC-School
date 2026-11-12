package com.mcschool.flashcard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicRateLimitFilterTest {
    private static final FilterChain OK = (req, res) -> { };

    private static int call(PublicRateLimitFilter filter, String method, String path, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (forwardedFor != null) request.addHeader("X-Forwarded-For", forwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, OK);
        return response.getStatus();
    }

    @Test
    void blocksTheEleventhLoginAttemptButNotOtherClients() throws Exception {
        var filter = new PublicRateLimitFilter(true);
        for (int i = 0; i < 10; i++) assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "9.9.9.9"));
        assertEquals(429, call(filter, "POST", "/api/v1/auth/login", "9.9.9.9"));
        assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "8.8.8.8"));
    }

    @Test
    void spoofedLeftmostForwardedAddressDoesNotEscapeTheLimit() throws Exception {
        var filter = new PublicRateLimitFilter(true);
        for (int i = 0; i < 10; i++) call(filter, "POST", "/api/v1/auth/login", "1.1.1." + i + ", 9.9.9.9");
        assertEquals(429, call(filter, "POST", "/api/v1/auth/login", "7.7.7.7, 9.9.9.9"));
    }

    @Test
    void windowSlidesAndLimitReopens() throws Exception {
        var clock = new MutableClock();
        var filter = new PublicRateLimitFilter(true, clock);
        for (int i = 0; i < 10; i++) call(filter, "POST", "/api/v1/auth/login", "9.9.9.9");
        assertEquals(429, call(filter, "POST", "/api/v1/auth/login", "9.9.9.9"));
        clock.now = clock.now.plusSeconds(301);
        assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "9.9.9.9"));
    }

    @Test
    void invitationTokenGuessingIsThrottledAndOrdinaryRoutesAreUntouched() throws Exception {
        var filter = new PublicRateLimitFilter(true);
        for (int i = 0; i < 30; i++) assertEquals(200, call(filter, "GET", "/api/v1/public/trial-invitations/t" + i, "9.9.9.9"));
        assertEquals(429, call(filter, "GET", "/api/v1/public/trial-invitations/tx", "9.9.9.9"));
        for (int i = 0; i < 100; i++) assertEquals(200, call(filter, "GET", "/api/v1/students", "9.9.9.9"));
    }

    @Test
    void canBeDisabled() throws Exception {
        var filter = new PublicRateLimitFilter(false);
        for (int i = 0; i < 50; i++) assertEquals(200, call(filter, "POST", "/api/v1/auth/login", "9.9.9.9"));
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
