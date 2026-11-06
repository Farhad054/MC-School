package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.groups.StudentGroup;
import com.mcschool.flashcard.groups.StudentGroupRepository;
import com.mcschool.flashcard.lessons.dto.CreateLessonRequest;
import com.mcschool.flashcard.lessons.dto.CreateLessonSeriesRequest;
import com.mcschool.flashcard.lessons.dto.GroupLessonResponse;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Scheduling writes to the same native timetable and lesson records used by the website. */
@Service
public class McpLessonScheduleService {
    private static final ZoneId SCHOOL_ZONE = ZoneId.of("Europe/Berlin");
    private static final List<String> TOOLS = List.of("create_lesson", "create_lesson_series",
            "update_lesson", "delete_lesson", "assign_lesson");
    private final ManualLessonService lessons;
    private final GoogleCalendarLessonService calendar;
    private final UserRepository users;
    private final StudentGroupRepository groups;
    private final LessonHistoryRepository history;
    private final McpAccessService access;
    private final McpOperationReceiptService receipts;
    private final JdbcTemplate jdbc;

    public McpLessonScheduleService(ManualLessonService lessons, GoogleCalendarLessonService calendar,
            UserRepository users, StudentGroupRepository groups, LessonHistoryRepository history,
            McpAccessService access, McpOperationReceiptService receipts, JdbcTemplate jdbc) {
        this.lessons = lessons;
        this.calendar = calendar;
        this.users = users;
        this.groups = groups;
        this.history = history;
        this.access = access;
        this.receipts = receipts;
        this.jdbc = jdbc;
    }

    public static boolean handles(String name) { return TOOLS.contains(name); }

    public static List<Map<String, Object>> definitions() {
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("teacherId", Map.of("type", "string", "format", "uuid", "description",
                "Teacher UUID. Teachers default to themselves. Admins must select a teacher for trial lessons, updates or deletion; student/group creation can infer the target's teacher."));
        common.put("idempotencyKey", Map.of("type", "string", "minLength", 1, "maxLength", 128,
                "description", "Stable identifier for this requested change. Reuse unchanged arguments and key on a retry; use a new key for another intended change."));
        Map<String, Object> clock = Map.of(
                "date", Map.of("type", "string", "format", "date", "description", "Lesson date YYYY-MM-DD in Europe/Berlin."),
                "startTime", Map.of("type", "string", "pattern", "^([01][0-9]|2[0-3]):[0-5][0-9]$", "description", "Exact Berlin local start time HH:mm. Nonexistent or ambiguous DST times are rejected."),
                "durationMinutes", Map.of("type", "integer", "minimum", 1, "maximum", 1440));
        Map<String, Object> target = Map.of(
                "targetType", Map.of("type", "string", "enum", List.of("student", "group", "trial"), "description", "Use student/group with an exact targetId from find_homework_targets or find_students. Trial means no registered pupil/group yet."),
                "targetId", Map.of("type", "string", "format", "uuid", "description", "Required for student/group and forbidden for trial."));
        Map<String, Object> create = new LinkedHashMap<>(common);
        create.put("title", Map.of("type", "string", "minLength", 1, "maxLength", 500));
        create.putAll(clock); create.putAll(target);
        Map<String, Object> series = new LinkedHashMap<>(create);
        series.remove("date");
        series.put("startDate", Map.of("type", "string", "format", "date", "description", "Inclusive first date in Europe/Berlin."));
        series.put("period", Map.of("type", "string", "enum", List.of("TWO_WEEKS", "THREE_WEEKS", "MONTH"), "description", "Finite interval from startDate, exclusive of startDate plus two/three weeks or one calendar month."));
        series.put("weekdays", Map.of("type", "array", "items", Map.of("type", "integer", "minimum", 1, "maximum", 7), "uniqueItems", true, "minItems", 1, "maxItems", 7, "description", "Berlin weekdays: Monday=1 through Sunday=7. Local start time stays fixed across DST."));
        Map<String, Object> event = new LinkedHashMap<>(common);
        event.put("eventId", Map.of("type", "string", "minLength", 1, "maxLength", 255, "description", "Exact eventId from find_lessons, not a PDF draft ID."));
        Map<String, Object> update = new LinkedHashMap<>(event); update.putAll(clock);
        Map<String, Object> delete = new LinkedHashMap<>(event);
        delete.put("confirm", Map.of("type", "boolean", "const", true, "description", "True only when the user explicitly requested deletion of this lesson."));
        Map<String, Object> assign = new LinkedHashMap<>(event);
        assign.put("targetType", Map.of("type", "string", "enum", List.of("student", "group")));
        assign.put("targetId", target.get("targetId"));
        assign.put("applyToSeries", Map.of("type", "boolean", "description", "Required true if the imported event belongs to a recurring Google binding; assignment then applies to that binding's whole series."));
        return List.of(
                tool("create_lesson", "Schedule one lesson on the Mindcrafti website for the selected teacher. Does not generate PDFs or create a Google Calendar event. OAuth teacher/admin sign-in required; import API keys cannot schedule.", create,
                        List.of("title", "date", "startTime", "durationMinutes", "targetType", "idempotencyKey"), false),
                tool("create_lesson_series", "Schedule a finite series of website lessons atomically at exact Berlin local times. Does not generate PDF materials or Google Calendar events. OAuth teacher/admin sign-in required.", series,
                        List.of("title", "startDate", "startTime", "durationMinutes", "period", "weekdays", "targetType", "idempotencyKey"), false),
                tool("update_lesson", "Change one existing lesson's date, start time and duration in the website timetable. Preserves eventId, materials and classroom archive; does not rename the lesson or edit Google Calendar.", update,
                        List.of("eventId", "date", "startTime", "durationMinutes", "idempotencyKey"), false),
                tool("delete_lesson", "Explicitly remove one lesson from the website schedule and close its open classroom for everyone. Materials and history remain. Does not delete a Google Calendar event. Requires confirm=true and OAuth sign-in.", delete,
                        List.of("eventId", "confirm", "idempotencyKey"), true),
                tool("assign_lesson", "Assign an existing lesson to the same teacher's student or group. For imported recurring lessons, applyToSeries=true explicitly changes the whole saved binding; Google Calendar itself is unchanged.", assign,
                        List.of("eventId", "targetType", "targetId", "idempotencyKey"), false));
    }

    @Transactional
    public Object call(String name, Map<String, Object> args, User oauthUser, boolean apiKey) {
        if (!handles(name)) throw new IllegalArgumentException("Unknown scheduling tool");
        if (apiKey) throw new IllegalArgumentException("Scheduling requires OAuth teacher/admin sign-in; import API keys cannot schedule lessons");
        AuthenticatedUser actor = access.requireActor(oauthUser);
        Map<String, Object> definition = definitions().get(TOOLS.indexOf(name));
        @SuppressWarnings("unchecked") Map<String, Object> inputSchema = (Map<String, Object>) definition.get("inputSchema");
        @SuppressWarnings("unchecked") Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        if (!properties.keySet().containsAll(args.keySet())) throw new IllegalArgumentException("Unsupported scheduling arguments");
        String key = text(args, "idempotencyKey", 128);
        boolean creating = name.equals("create_lesson") || name.equals("create_lesson_series");
        Target target = creating || name.equals("assign_lesson") ? target(args, creating) : null;
        UUID selectedTeacher = optionalUuid(args, "teacherId");
        if (selectedTeacher == null && actor.role() == Role.ADMIN && target != null) selectedTeacher = target.teacherId();
        AuthenticatedUser teacher = access.requireTeacher(actor, selectedTeacher);
        if (target != null) requireTarget(teacher, target);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("teacherId", teacher.id().toString());
        String eventId = creating ? null : text(args, "eventId", 255);
        if (eventId != null) normalized.put("eventId", eventId);
        if (target != null) {
            normalized.put("targetType", target.type());
            normalized.put("targetId", target.id() == null ? null : target.id().toString());
        }
        if (name.equals("delete_lesson")) {
            if (!Boolean.TRUE.equals(args.get("confirm"))) throw new IllegalArgumentException("delete_lesson requires confirm=true after explicit user deletion intent");
            normalized.put("confirm", true);
            return execute(actor, teacher, name, key, normalized, () -> ownedEvent(teacher, eventId, true), () -> {
                lessons.delete(teacher, eventId);
                return Map.of("teacherId", teacher.id().toString(), "eventId", eventId, "removedFromSchedule", true,
                        "materialsPreserved", true, "googleCalendarChanged", false);
            });
        }
        if (name.equals("assign_lesson")) {
            if (args.containsKey("applyToSeries") && !(args.get("applyToSeries") instanceof Boolean)) throw new IllegalArgumentException("applyToSeries must be a boolean");
            boolean applyToSeries = Boolean.TRUE.equals(args.get("applyToSeries"));
            normalized.put("applyToSeries", applyToSeries);
            return execute(actor, teacher, name, key, normalized, () -> {
                requireTarget(teacher, target);
                requireAssignmentScope(ownedEvent(teacher, eventId, false), applyToSeries);
            }, () -> {
                GroupLessonResponse event = ownedEvent(teacher, eventId, false).toResponse();
                boolean nativeLesson = eventId.startsWith(ManualLessonService.EVENT_PREFIX);
                if (target.type().equals("student")) {
                    if (nativeLesson) lessons.bindStudent(teacher, eventId, target.id());
                    else calendar.bindStudent(teacher, event.bindingKey(), target.id());
                } else {
                    if (nativeLesson) lessons.bindGroup(teacher, eventId, target.id());
                    else calendar.bindGroup(teacher, event.bindingKey(), target.id());
                }
                return Map.of("teacherId", teacher.id().toString(), "eventId", eventId,
                        "bindingKey", event.bindingKey(), "targetType", target.type(), "targetId", target.id().toString(),
                        "assignmentSaved", true, "appliesToSeries", !nativeLesson && !eventId.equals(event.bindingKey()), "googleCalendarChanged", false);
            });
        }
        LocalTime startTime = time(args);
        int duration = integer(args.get("durationMinutes"), "durationMinutes", 1, 1440);
        normalized.put("startTime", startTime.toString()); normalized.put("durationMinutes", duration);
        if (name.equals("update_lesson")) {
            LocalDate date = date(args, "date"); Instant start = instant(date, startTime);
            normalized.put("date", date.toString());
            return execute(actor, teacher, name, key, normalized, () -> ownedEvent(teacher, eventId, false),
                    () -> response(teacher, lessons.reschedule(teacher, eventId, start, start.plusSeconds(duration * 60L))));
        }
        String title = text(args, "title", 500); normalized.put("title", title);
        UUID studentId = target.type().equals("student") ? target.id() : null;
        UUID groupId = target.type().equals("group") ? target.id() : null;
        if (name.equals("create_lesson")) {
            LocalDate date = date(args, "date"); Instant start = instant(date, startTime);
            normalized.put("date", date.toString());
            return execute(actor, teacher, name, key, normalized, () -> requireTarget(teacher, target),
                    () -> response(teacher, lessons.create(teacher, new CreateLessonRequest(title, start,
                            start.plusSeconds(duration * 60L), groupId, studentId))));
        }
        LocalDate startDate = date(args, "startDate");
        CreateLessonSeriesRequest.Period period;
        try { period = CreateLessonSeriesRequest.Period.valueOf(text(args, "period", 20)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("period must be TWO_WEEKS, THREE_WEEKS or MONTH"); }
        Set<Integer> weekdays = weekdays(args.get("weekdays"));
        normalized.put("startDate", startDate.toString()); normalized.put("period", period.name()); normalized.put("weekdays", new ArrayList<>(weekdays));
        CreateLessonSeriesRequest request = new CreateLessonSeriesRequest(title, startDate, duration, period, weekdays, groupId, studentId, startTime);
        return execute(actor, teacher, name, key, normalized, () -> requireTarget(teacher, target), () -> {
            List<GroupLessonResponse> created = lessons.createSeries(teacher, request);
            return Map.of("teacherId", teacher.id().toString(), "lessons", created.stream().map(McpLessonScheduleService::lesson).toList(),
                    "count", created.size(), "timeZone", SCHOOL_ZONE.getId(), "googleCalendarChanged", false);
        });
    }

    private Object execute(AuthenticatedUser actor, AuthenticatedUser teacher, String name, String key,
            Map<String, Object> normalized, Runnable recheck, java.util.function.Supplier<Object> work) {
        return receipts.execute(actor, teacher.id(), name, key, normalized, recheck,
                () -> access.asTeacher(actor, teacher, work));
    }

    private Target target(Map<String, Object> args, boolean allowTrial) {
        String type = text(args, "targetType", 20);
        UUID id = optionalUuid(args, "targetId");
        if (type.equals("trial") && allowTrial) {
            if (id != null) throw new IllegalArgumentException("Trial lessons cannot have targetId");
            return new Target(type, null, null);
        }
        if (id == null) throw new IllegalArgumentException("targetId is required for a student or group");
        if (type.equals("student")) {
            User student = users.findById(id).filter(user -> user.getRole() == Role.STUDENT && !user.isArchived() && user.getTeacher() != null)
                    .orElseThrow(() -> new IllegalArgumentException("Student not found"));
            return new Target(type, id, student.getTeacher().getId());
        }
        if (type.equals("group")) {
            StudentGroup group = groups.findById(id).orElseThrow(() -> new IllegalArgumentException("Group not found"));
            return new Target(type, id, group.getTeacher().getId());
        }
        throw new IllegalArgumentException("targetType must be student, group" + (allowTrial ? " or trial" : ""));
    }

    private void requireTarget(AuthenticatedUser teacher, Target target) {
        if (target.type().equals("trial")) return;
        // A receipt claim can wait while another transaction transfers the pupil/group.
        // Scalar SQL bypasses a previously loaded JPA teacher proxy after that wait.
        String sql = target.type().equals("student")
                ? "SELECT count(*) FROM users WHERE id = ? AND teacher_id = ? AND role = 'STUDENT' AND archived = false"
                : "SELECT count(*) FROM student_groups WHERE id = ? AND teacher_id = ?";
        Integer count = jdbc.queryForObject(sql, Integer.class, target.id(), teacher.id());
        if (count == null || count != 1) throw new IllegalArgumentException("Target does not belong to the selected teacher");
    }

    private LessonHistoryEntry ownedEvent(AuthenticatedUser teacher, String eventId, boolean includeDeleted) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM lesson_history WHERE teacher_id = ? AND event_id = ?"
                        + (includeDeleted ? "" : " AND deleted_at IS NULL"), Integer.class, teacher.id(), eventId);
        if (count == null || count != 1) throw new IllegalArgumentException("Lesson was not found under the selected teacher; use find_lessons first");
        return history.findByTeacherIdAndEventId(teacher.id(), eventId)
                .filter(item -> includeDeleted || item.getDeletedAt() == null)
                .orElseThrow(() -> new IllegalArgumentException("Lesson was not found under the selected teacher; use find_lessons first"));
    }

    private void requireAssignmentScope(LessonHistoryEntry event, boolean applyToSeries) {
        String binding = event.toResponse().bindingKey();
        if (!event.getEventId().startsWith(ManualLessonService.EVENT_PREFIX) && !event.getEventId().equals(binding) && !applyToSeries)
            throw new IllegalArgumentException("This imported lesson uses a recurring binding; assigning it changes the whole series. Explicit applyToSeries=true is required");
    }

    private static Map<String, Object> response(AuthenticatedUser teacher, GroupLessonResponse result) {
        return Map.of("teacherId", teacher.id().toString(), "eventId", result.eventId(), "lesson", lesson(result),
                "timeZone", SCHOOL_ZONE.getId(), "googleCalendarChanged", false);
    }

    private static Map<String, Object> lesson(GroupLessonResponse result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", result.eventId()); value.put("bindingKey", result.bindingKey()); value.put("title", result.title());
        value.put("startsAt", result.startsAt().toString()); value.put("endsAt", result.endsAt().toString());
        value.put("date", result.startsAt().atZone(SCHOOL_ZONE).toLocalDate().toString());
        value.put("startTime", result.startsAt().atZone(SCHOOL_ZONE).toLocalTime().toString());
        value.put("durationMinutes", java.time.Duration.between(result.startsAt(), result.endsAt()).toMinutes());
        value.put("studentId", result.studentId() == null ? null : result.studentId().toString());
        value.put("studentName", result.studentName()); value.put("groupId", result.groupId() == null ? null : result.groupId().toString());
        value.put("groupName", result.groupName()); value.put("participantStudentIds", result.participantStudentIds());
        return value;
    }

    private static Instant instant(LocalDate date, LocalTime time) {
        var local = date.atTime(time); var offsets = SCHOOL_ZONE.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("Invalid or ambiguous Berlin lesson time; select a different local time");
        return local.atOffset(offsets.get(0)).toInstant();
    }
    private static String text(Map<String, Object> args, String name, int limit) {
        if (!(args.get(name) instanceof String value) || value.isBlank() || value.length() > limit)
            throw new IllegalArgumentException(name + " must be a nonblank string of at most " + limit + " characters");
        return value.strip();
    }
    private static UUID optionalUuid(Map<String, Object> args, String name) {
        if (!args.containsKey(name)) return null;
        try { return UUID.fromString(text(args, name, 36)); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException(name + " must be a UUID"); }
    }
    private static LocalDate date(Map<String, Object> args, String name) {
        try {
            String value = text(args, name, 10);
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) { throw new IllegalArgumentException(name + " must be a valid YYYY-MM-DD date"); }
    }
    private static LocalTime time(Map<String, Object> args) {
        String value = text(args, "startTime", 5);
        if (!value.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("startTime must use HH:mm Berlin local time");
        return LocalTime.parse(value);
    }
    private static int integer(Object raw, String name, int min, int max) {
        try {
            if (!(raw instanceof Number)) throw new IllegalArgumentException();
            int value = new BigDecimal(raw.toString()).intValueExact();
            if (value < min || value > max) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException(name + " must be an integer from " + min + " through " + max); }
    }
    private static Set<Integer> weekdays(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 7) throw new IllegalArgumentException("weekdays must contain one through seven unique days");
        Set<Integer> result = new TreeSet<>();
        for (Object item : list) if (!result.add(integer(item, "weekdays", 1, 7))) throw new IllegalArgumentException("weekdays cannot contain duplicates");
        return result;
    }
    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties,
            List<String> required, boolean destructive) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false),
                "annotations", Map.of("readOnlyHint", false, "destructiveHint", destructive, "idempotentHint", true, "openWorldHint", false));
    }
    private record Target(String type, UUID id, UUID teacherId) {}
}
