package com.mcschool.flashcard.learningtime;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.homeworks.HomeworkRepository;
import com.mcschool.flashcard.study.StudySessionRepository;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal activity telemetry for children's learning progress: duration, type
 * and the exercise ID only. No mouse coordinates, answers or document content.
 */
@RestController
@RequestMapping("/api/v1/learning-time")
public class LearningTimeController {

    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Europe/Berlin");

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final StudySessionRepository sessions;
    private final HomeworkRepository homeworks;

    public LearningTimeController(JdbcTemplate jdbc, UserRepository users,
                                  StudySessionRepository sessions, HomeworkRepository homeworks) {
        this.jdbc = jdbc;
        this.users = users;
        this.sessions = sessions;
        this.homeworks = homeworks;
    }

    public record ActivityEvent(UUID eventId, String kind, UUID referenceId, Integer seconds) {}

    public record StudentTimeSummary(UUID studentId, String studentName,
                                     long cardSeconds, long homeworkSeconds,
                                     int cardSessions, int homeworkAssignments,
                                     int activeDays, Instant lastActivityAt) {}

    public record ActivityDetail(String kind, UUID referenceId, long seconds,
                                 String label, Instant lastActivityAt, Instant completedAt) {}

    public record StudentTimeReport(StudentTimeSummary summary, List<ActivityDetail> details) {}

    @PostMapping("/events")
    @PreAuthorize("hasRole('STUDENT')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@AuthenticationPrincipal AuthenticatedUser caller,
                       @RequestBody ActivityEvent event) {
        if (event == null || event.eventId() == null || event.referenceId() == null
                || event.seconds() == null || event.seconds() < 1 || event.seconds() > 120
                || !("CARDS".equals(event.kind()) || "HOMEWORK".equals(event.kind()))) {
            throw new IllegalArgumentException("Invalid learning time event");
        }

        // A student may report time only for their own card session or homework.
        boolean owned = "CARDS".equals(event.kind())
                ? sessions.findByIdAndStudentId(event.referenceId(), caller.id()).isPresent()
                : homeworks.findByIdAndStudentId(event.referenceId(), caller.id()).isPresent();
        if (!owned) throw new ResourceNotFoundException("Learning activity not found");

        jdbc.update("""
                INSERT INTO learning_activity_events
                  (event_id, student_id, activity_type, reference_id, duration_seconds)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), caller.id(), event.kind(),
                event.referenceId(), event.seconds());
    }

    @GetMapping("/admin")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<StudentTimeSummary> schoolSummary(@RequestParam(defaultValue = "30") int days) {
        return querySummary(startAt(days), null);
    }

    @GetMapping("/students/{studentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    @Transactional(readOnly = true)
    public StudentTimeReport studentReport(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable UUID studentId,
                                           @RequestParam(defaultValue = "30") int days) {
        User student = users.findById(studentId)
                .filter(user -> user.getRole() == Role.STUDENT && !user.isArchived())
                .orElseThrow(() -> new ResourceNotFoundException("Student not found"));
        if (caller.role() != Role.ADMIN
                && (student.getTeacher() == null || !caller.id().equals(student.getTeacher().getId()))) {
            throw new ResourceNotFoundException("Student not found");
        }
        Timestamp from = startAt(days);
        List<StudentTimeSummary> matching = querySummary(from, studentId);
        return new StudentTimeReport(matching.get(0), queryDetails(from, studentId));
    }

    private static Timestamp startAt(int days) {
        if (days < 1 || days > 365) throw new IllegalArgumentException("days must be from 1 to 365");
        LocalDate beginning = LocalDate.now(SCHOOL_ZONE).minusDays(days - 1L);
        return Timestamp.from(beginning.atStartOfDay(SCHOOL_ZONE).toInstant());
    }

    private List<StudentTimeSummary> querySummary(Timestamp from, UUID studentId) {
        String sql = """
                SELECT u.id AS student_id, u.full_name AS student_name,
                  COALESCE(SUM(CASE WHEN e.activity_type='CARDS' THEN e.duration_seconds ELSE 0 END),0)::bigint AS card_seconds,
                  COALESCE(SUM(CASE WHEN e.activity_type='HOMEWORK' THEN e.duration_seconds ELSE 0 END),0)::bigint AS homework_seconds,
                  COUNT(DISTINCT CASE WHEN e.activity_type='CARDS' THEN e.reference_id END)::int AS card_sessions,
                  COUNT(DISTINCT CASE WHEN e.activity_type='HOMEWORK' THEN e.reference_id END)::int AS homework_assignments,
                  COUNT(DISTINCT (e.recorded_at AT TIME ZONE 'Europe/Berlin')::date)::int AS active_days,
                  MAX(e.recorded_at) AS last_activity
                FROM users u
                LEFT JOIN learning_activity_events e
                  ON e.student_id = u.id AND e.recorded_at >= ?
                WHERE u.role='STUDENT' AND u.archived=FALSE
                """ + (studentId == null ? "" : " AND u.id = ?")
                + " GROUP BY u.id,u.full_name ORDER BY u.full_name";
        var mapper = (org.springframework.jdbc.core.RowMapper<StudentTimeSummary>) (rs, n) ->
                new StudentTimeSummary(
                        rs.getObject("student_id", UUID.class),
                        rs.getString("student_name"),
                        rs.getLong("card_seconds"),
                        rs.getLong("homework_seconds"),
                        rs.getInt("card_sessions"),
                        rs.getInt("homework_assignments"),
                        rs.getInt("active_days"),
                        timestamp(rs.getTimestamp("last_activity")));
        return studentId == null
                ? jdbc.query(sql, mapper, from)
                : jdbc.query(sql, mapper, from, studentId);
    }

    private List<ActivityDetail> queryDetails(Timestamp from, UUID studentId) {
        return jdbc.query("""
                SELECT e.activity_type, e.reference_id,
                       SUM(e.duration_seconds)::bigint AS seconds,
                       MAX(e.recorded_at) AS last_activity,
                       h.start_date AS homework_date,
                       h.worksheet_filename, h.submitted_at,
                       s.total_cards, s.completed_at
                FROM learning_activity_events e
                LEFT JOIN homeworks h
                  ON e.activity_type='HOMEWORK' AND h.id=e.reference_id
                LEFT JOIN study_sessions s
                  ON e.activity_type='CARDS' AND s.id=e.reference_id
                WHERE e.student_id=? AND e.recorded_at >= ?
                GROUP BY e.activity_type, e.reference_id, h.start_date,
                         h.worksheet_filename, h.submitted_at,
                         s.total_cards, s.completed_at
                ORDER BY MAX(e.recorded_at) DESC
                LIMIT 150
                """, (rs, n) -> {
            String kind = rs.getString("activity_type");
            String label;
            Instant completedAt;
            if ("HOMEWORK".equals(kind)) {
                label = rs.getString("homework_date") + " · "
                        + (rs.getString("worksheet_filename") == null
                        ? "Домашняя работа" : rs.getString("worksheet_filename"));
                completedAt = timestamp(rs.getTimestamp("submitted_at"));
            } else {
                label = "Карточки · " + rs.getInt("total_cards") + " шт.";
                completedAt = timestamp(rs.getTimestamp("completed_at"));
            }
            return new ActivityDetail(kind, rs.getObject("reference_id", UUID.class),
                    rs.getLong("seconds"), label,
                    timestamp(rs.getTimestamp("last_activity")), completedAt);
        }, studentId, from);
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
