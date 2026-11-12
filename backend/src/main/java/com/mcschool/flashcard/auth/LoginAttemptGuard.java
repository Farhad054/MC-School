package com.mcschool.flashcard.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-account brute-force brake. The IP throttle ({@code PublicRateLimitFilter})
 * stops one machine; this stops many machines aimed at one login. After
 * {@value #MAX_FAILURES} wrong passwords inside the window the account refuses
 * further attempts until the window passes, and a success clears the count.
 * In memory and per instance, which is enough to make guessing impractical.
 */
final class LoginAttemptGuard {
    static final int MAX_FAILURES = 8;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 50_000;

    private record Entry(int failures, long windowStartMs) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginAttemptGuard() { this(Clock.systemUTC()); }
    LoginAttemptGuard(Clock clock) { this.clock = clock; }

    private static String key(String identifier) {
        return identifier == null ? "" : identifier.strip().toLowerCase(Locale.ROOT);
    }

    /** True while this login is locked out. Does not count as an attempt. */
    boolean locked(String identifier) {
        Entry entry = entries.get(key(identifier));
        if (entry == null) return false;
        if (clock.millis() - entry.windowStartMs() > WINDOW.toMillis()) {
            entries.remove(key(identifier), entry);
            return false;
        }
        return entry.failures() >= MAX_FAILURES;
    }

    void failed(String identifier) {
        long now = clock.millis();
        if (entries.size() > MAX_TRACKED) entries.values().removeIf(e -> now - e.windowStartMs() > WINDOW.toMillis());
        entries.merge(key(identifier), new Entry(1, now), (old, fresh) ->
                now - old.windowStartMs() > WINDOW.toMillis() ? fresh : new Entry(old.failures() + 1, old.windowStartMs()));
    }

    void succeeded(String identifier) { entries.remove(key(identifier)); }
}
