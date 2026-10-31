package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.auth.OnlineClassPilotAuthorizationManager;
import com.mcschool.flashcard.liveclasses.OnlineClassAccessService;
import com.mcschool.flashcard.users.Role;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Additional live ownership checks, including before an idempotent receipt may be replayed. */
@Component
class McpSiteFunctionTargetGuard {
    private final JdbcTemplate jdbc;
    private final OnlineClassAccessService classes;
    private final OnlineClassPilotAuthorizationManager classroomGate;
    private final GoogleCalendarLessonService lessons;

    McpSiteFunctionTargetGuard(JdbcTemplate jdbc, OnlineClassAccessService classes,
                               OnlineClassPilotAuthorizationManager classroomGate,
                               GoogleCalendarLessonService lessons) {
        this.jdbc = jdbc;
        this.classes = classes;
        this.classroomGate = classroomGate;
        this.lessons = lessons;
    }

    void check(McpSiteFunctionRegistry.Function function, AuthenticatedUser caller, Map<String, Object> arguments) {
        if (function.classroomGate() && (caller.role() != Role.TEACHER || !classroomGate.isEnabledForTeacher(caller.id()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Classroom access is disabled for this account");
        }
        // Native school-wide administrative operations are protected by their ADMIN policy.
        if (caller.role() != Role.TEACHER) return;
        checkTargets(function, caller, arguments);
        if (function.domain().equals("monthly_plans")) {
            Object target = arguments.get("targetId");
            if (target != null) {
                String type = String.valueOf(arguments.get("targetType"));
                if ("student".equalsIgnoreCase(type)) student(caller, uuid(target));
                else if ("group".equalsIgnoreCase(type)) group(caller, uuid(target));
                else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid monthly plan targetType");
            }
        }
    }

    private void checkTargets(McpSiteFunctionRegistry.Function function, AuthenticatedUser caller, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object id = entry.getValue();
                if (id == null) continue;
                switch (key) {
                    case "studentId" -> student(caller, uuid(id));
                    case "studentIds" -> {
                        if (!(id instanceof Collection<?> ids)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "studentIds must be an array");
                        ids.forEach(studentId -> student(caller, uuid(studentId)));
                    }
                    case "groupId" -> group(caller, uuid(id));
                    case "homeworkId" -> require("SELECT count(*) FROM homeworks h JOIN users s ON s.id=h.student_id WHERE h.id=? AND s.teacher_id=?", uuid(id), caller.id());
                    case "cardId" -> require("SELECT count(*) FROM cards c JOIN users s ON s.id=c.student_id WHERE c.id=? AND s.teacher_id=?", uuid(id), caller.id());
                    case "classId" -> classes.requireHost(caller, uuid(id));
                    case "cameraId" -> require("SELECT count(*) FROM notebook_cameras c JOIN users u ON u.id=c.owner_id WHERE c.id=? AND c.teacher_id=? AND (u.id=c.teacher_id OR u.teacher_id=c.teacher_id)", uuid(id), caller.id());
                    case "eventId" -> {
                        // Transferred lesson history is intentionally authorized by its dedicated history service.
                        if (!function.domain().equals("lesson_history")) event(caller, String.valueOf(id));
                    }
                    case "messageId" -> {
                        if (function.domain().equals("parent_messages")) require("SELECT count(*) FROM parent_teacher_messages m JOIN users s ON s.id=m.student_id WHERE m.id=? AND s.teacher_id=?", uuid(id), caller.id());
                    }
                    default -> { }
                }
                // Only the declared request body can contain additional target identities. Never inspect document JSON content.
                if (key.equals("body")) checkTargets(function, caller, id);
            }
        }
    }

    private void event(AuthenticatedUser caller, String eventId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM lesson_history WHERE teacher_id=? AND event_id=?", Long.class, caller.id(), eventId);
        if (count != null && count > 0) return;
        // Calendar lessons may not yet have a local history row. Reuse the real teacher's listing.
        if (lessons.listGroupLessons(caller).stream().noneMatch(lesson -> eventId.equals(lesson.eventId()))) denied();
    }

    private void student(AuthenticatedUser caller, UUID studentId) {
        require("SELECT count(*) FROM users WHERE id=? AND teacher_id=? AND role='STUDENT'", studentId, caller.id());
    }

    private void group(AuthenticatedUser caller, UUID groupId) {
        require("SELECT count(*) FROM student_groups WHERE id=? AND teacher_id=?", groupId, caller.id());
    }

    private void require(String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        if (count == null || count == 0) denied();
    }

    private static UUID uuid(Object value) {
        try { return UUID.fromString(String.valueOf(value)); }
        catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target identifier"); }
    }

    private static void denied() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not available to this teacher"); }
}
