package com.mcschool.flashcard.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sliding-window throttle for the endpoints that anyone on the internet can
 * reach without a token: password login, account activation, the trial-lesson
 * invitation links and the public lead forms. Without it a password or an
 * invitation token can be guessed at full network speed.
 *
 * <p>State is in memory, per backend instance. That is deliberate: it needs no
 * new infrastructure, and it only has to blunt guessing, not be an exact quota.
 * The client key is the right-most X-Forwarded-For entry (the one appended by
 * the platform's own proxy, which a caller cannot forge), else the socket peer.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PublicRateLimitFilter extends OncePerRequestFilter {
    private record Rule(String name, int maxRequests, Duration window) {}

    private static final Rule LOGIN = new Rule("login", 10, Duration.ofMinutes(5));
    private static final Rule INVITATION = new Rule("invitation", 30, Duration.ofMinutes(1));
    private static final Rule LEADS = new Rule("leads", 30, Duration.ofMinutes(1));
    private static final int MAX_TRACKED_KEYS = 20_000;

    private final boolean enabled;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Autowired
    public PublicRateLimitFilter(@Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this(enabled, Clock.systemUTC());
    }

    PublicRateLimitFilter(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    static Rule ruleFor(String method, String path) {
        if ("POST".equals(method) && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/activate"))) return LOGIN;
        if (path.startsWith("/api/v1/public/trial-invitations/")) return INVITATION;
        if ("POST".equals(method) && (path.equals("/api/v1/public/trial-leads") || path.equals("/api/v1/public/site-visits"))) return LEADS;
        return null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || ruleFor(request.getMethod(), request.getRequestURI()) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request.getMethod(), request.getRequestURI());
        long retryAfter = rule == null ? 0 : consume(rule, clientKey(request));
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"message\":\"Too many requests. Try again later.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Records one hit; returns 0 when allowed, else the seconds until a slot frees up. */
    private long consume(Rule rule, String client) {
        long now = clock.millis();
        long windowMs = rule.window().toMillis();
        if (hits.size() > MAX_TRACKED_KEYS) hits.values().removeIf(q -> q.isEmpty() || q.peekLast() < now - windowMs * 12);
        Deque<Long> queue = hits.computeIfAbsent(rule.name() + '|' + client, k -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst() <= now - windowMs) queue.pollFirst();
            if (queue.size() >= rule.maxRequests()) {
                return Math.max(1, (queue.peekFirst() + windowMs - now + 999) / 1000);
            }
            queue.addLast(now);
            return 0;
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty() && last.length() <= 64) return last;
        }
        return request.getRemoteAddr();
    }
}
