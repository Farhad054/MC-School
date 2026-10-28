package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.*;

import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Disposable migrated database: receipt claims and real scheduling writes must commit together. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class McpLessonSchedulePersistenceIntegrationTest {
    @Container private static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired private McpLessonScheduleService service;
    @Autowired private UserRepository users;
    @Autowired private LessonHistoryRepository history;
    @Autowired private LessonPreparationRepository preparations;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GoogleCalendarOAuthService googleOAuth;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", DATABASE::getJdbcUrl);
        properties.add("spring.datasource.username", DATABASE::getUsername);
        properties.add("spring.datasource.password", DATABASE::getPassword);
    }

    private User teacher() {
        String unique = UUID.randomUUID().toString();
        return users.save(User.activeTeacher("MCP teacher", unique + "@test.local", unique, "unused"));
    }
    private Map<String, Object> create(String key) {
        return new LinkedHashMap<>(Map.of("title", "Fractions", "date", "2026-11-05", "startTime", "16:30",
                "durationMinutes", 60, "targetType", "trial", "idempotencyKey", key));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String name, Map<String, Object> args, User actor) {
        return (Map<String, Object>) service.call(name, args, actor, false);
    }
    private int count(String table, String field, UUID id) {
        // Table/column names are test constants, never tool inputs.
        return jdbc.queryForObject("select count(*) from " + table + " where " + field + "=?", Integer.class, id);
    }

    @Test
    void concurrentRetriesCreateExactlyOneLessonAndRejectChangedArguments() throws Exception {
        User teacher = teacher();
        Map<String, Object> args = create("concurrent-create");
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { ready.countDown(); go.await(); return call("create_lesson", args, teacher); });
            var second = pool.submit(() -> { ready.countDown(); go.await(); return call("create_lesson", args, teacher); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            Map<String, Object> original = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(original);
            assertThat(call("create_lesson", args, teacher)).isEqualTo(original);
            assertThat(count("lesson_history", "teacher_id", teacher.getId())).isEqualTo(1);
            assertThat(count("mcp_operation_receipts", "actor_id", teacher.getId())).isEqualTo(1);
            args.put("durationMinutes", 45);
            assertThatThrownBy(() -> call("create_lesson", args, teacher)).hasMessageContaining("different arguments");
            assertThat(count("lesson_history", "teacher_id", teacher.getId())).isEqualTo(1);
        } finally { go.countDown(); pool.shutdownNow(); }
    }

    @Test
    void invalidLaterDstOccurrenceRollsBackEntireSeriesAndItsReceipt() {
        User teacher = teacher();
        Map<String, Object> args = create("dst-series"); args.remove("date");
        args.put("startDate", "2026-03-22"); args.put("startTime", "02:30");
        args.put("period", "TWO_WEEKS"); args.put("weekdays", List.of(7));
        assertThatThrownBy(() -> call("create_lesson_series", args, teacher)).hasMessageContaining("ambiguous Berlin");
        assertThat(count("lesson_history", "teacher_id", teacher.getId())).isZero();
        assertThat(count("mcp_operation_receipts", "actor_id", teacher.getId())).isZero();
        args.put("startTime", "03:30");
        assertThat(call("create_lesson_series", args, teacher)).containsEntry("count", 2);
        assertThat(count("lesson_history", "teacher_id", teacher.getId())).isEqualTo(2);
        assertThat(count("mcp_operation_receipts", "actor_id", teacher.getId())).isEqualTo(1);
    }

    @Test
    void confirmedDeleteCanBeRetriedWhileStoredLessonMaterialsRemain() {
        User teacher = teacher();
        String eventId = String.valueOf(call("create_lesson", create("create-to-delete"), teacher).get("eventId"));
        LessonPreparation notes = LessonPreparation.create(teacher, eventId);
        notes.updateNotes("Stored homework", "Difficulties", "Plan", "Original transcript");
        preparations.save(notes);
        Map<String, Object> remove = Map.of("eventId", eventId, "idempotencyKey", "remove-one", "confirm", true);
        Map<String, Object> original = call("delete_lesson", remove, teacher);
        assertThat(call("delete_lesson", remove, teacher)).isEqualTo(original);
        assertThat(history.findByTeacherIdAndEventId(teacher.getId(), eventId).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(history.findAllByTeacherIdAndDeletedAtIsNullOrderByStartsAtAsc(teacher.getId())).isEmpty();
        assertThat(preparations.findByTeacherIdAndEventId(teacher.getId(), eventId).orElseThrow().getHomeworkNotes()).isEqualTo("Stored homework");
        assertThat(count("mcp_operation_receipts", "actor_id", teacher.getId())).isEqualTo(2);
    }

    @Test
    void cachedCreationDoesNotBypassCurrentOwnershipAfterPupilTransfer() {
        User teacher = teacher(); User nextTeacher = teacher();
        User pupil = users.save(User.managedStudent("Transferred pupil", teacher));
        Map<String, Object> args = create("before-transfer"); args.put("targetType", "student"); args.put("targetId", pupil.getId().toString());
        call("create_lesson", args, teacher);
        pupil.reassignTeacher(nextTeacher); users.save(pupil);
        assertThatThrownBy(() -> call("create_lesson", args, teacher)).hasMessageContaining("selected teacher");
        assertThat(count("lesson_history", "teacher_id", teacher.getId())).isEqualTo(1);
        assertThat(count("mcp_operation_receipts", "actor_id", teacher.getId())).isEqualTo(1);
    }
}
