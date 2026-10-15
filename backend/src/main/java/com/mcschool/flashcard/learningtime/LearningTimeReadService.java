package com.mcschool.flashcard.learningtime;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only, access-scoped queries for tracked active schoolwork time. */
@Service
public class LearningTimeReadService {
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private final JdbcTemplate jdbc;

    public record Breakdown(long cardSeconds, long homeworkSeconds) {
        public long totalSeconds() { return cardSeconds + homeworkSeconds; }
    }

    public LearningTimeReadService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Breakdown forDay(UUID studentId, LocalDate date) {
        return forPeriod(studentId, date, date);
    }

    public Breakdown forPeriod(UUID studentId, LocalDate from, LocalDate to) {
        Map<String, Long> result = new HashMap<>();
        jdbc.query("""
                SELECT activity_type, COALESCE(SUM(duration_seconds), 0) AS seconds
                FROM learning_activity_events
                WHERE student_id = ? AND recorded_at >= ? AND recorded_at < ?
                GROUP BY activity_type
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getString("activity_type"), rs.getLong("seconds")),
                studentId, timestamp(from), timestamp(to.plusDays(1)));
        return new Breakdown(result.getOrDefault("CARDS", 0L),
                result.getOrDefault("HOMEWORK", 0L));
    }

    /** Totals per PDF homework through the end of the requested day (or all time if date is null). */
    public Map<UUID, Long> homeworkByReference(UUID studentId, LocalDate throughDay) {
        Map<UUID, Long> result = new HashMap<>();
        if (throughDay == null) {
            jdbc.query("""
                    SELECT reference_id, COALESCE(SUM(duration_seconds), 0) AS seconds
                    FROM learning_activity_events
                    WHERE student_id = ? AND activity_type = 'HOMEWORK'
                    GROUP BY reference_id
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getObject("reference_id", UUID.class), rs.getLong("seconds")),
                    studentId);
        } else {
            jdbc.query("""
                    SELECT reference_id, COALESCE(SUM(duration_seconds), 0) AS seconds
                    FROM learning_activity_events
                    WHERE student_id = ? AND activity_type = 'HOMEWORK' AND recorded_at < ?
                    GROUP BY reference_id
                    """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getObject("reference_id", UUID.class), rs.getLong("seconds")),
                    studentId, timestamp(throughDay.plusDays(1)));
        }
        return result;
    }

    public long cardSessionSeconds(UUID studentId, UUID sessionId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(duration_seconds), 0)
                FROM learning_activity_events
                WHERE student_id = ? AND activity_type = 'CARDS' AND reference_id = ?
                """, Long.class, studentId, sessionId);
        return value == null ? 0L : value;
    }

    private Timestamp timestamp(LocalDate day) {
        Instant instant = day.atStartOfDay(ZONE).toInstant();
        return Timestamp.from(instant);
    }
}
