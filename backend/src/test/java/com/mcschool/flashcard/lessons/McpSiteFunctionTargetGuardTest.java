package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.auth.OnlineClassPilotAuthorizationManager;
import com.mcschool.flashcard.liveclasses.OnlineClassAccessService;
import com.mcschool.flashcard.users.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpSiteFunctionTargetGuardTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OnlineClassAccessService classes = mock(OnlineClassAccessService.class);
    private final OnlineClassPilotAuthorizationManager gate = mock(OnlineClassPilotAuthorizationManager.class);
    private final GoogleCalendarLessonService lessons = mock(GoogleCalendarLessonService.class);
    private final McpSiteFunctionTargetGuard guard = new McpSiteFunctionTargetGuard(jdbc, classes, gate, lessons);
    private final AuthenticatedUser teacher = new AuthenticatedUser(UUID.randomUUID(), "teacher@example.test", Role.TEACHER);

    @Test
    void directControllerAdapterStillHonorsGlobalClassroomFeatureSwitch() {
        assertThatThrownBy(() -> guard.check(function("lesson_board.start"), teacher, Map.of("eventId", "lesson")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(jdbc, lessons, classes);
        assertThatThrownBy(() -> guard.check(function("notebook_camera.list"), teacher, Map.of("eventId", "lesson")))
                .hasMessageContaining("403");
    }

    @Test
    void ownershipIsQueriedFreshForEveryRecheckIncludingReceiptReplay() {
        UUID studentId = UUID.randomUUID();
        String sql = "SELECT count(*) FROM users WHERE id=? AND teacher_id=? AND role='STUDENT'";
        when(jdbc.queryForObject(sql, Long.class, studentId, teacher.id())).thenReturn(1L, 0L);
        Map<String, Object> arguments = Map.of("studentId", studentId.toString(), "body", Map.of("fullName", "Renamed"));
        guard.check(function("students.updateStudentName"), teacher, arguments);
        assertThatThrownBy(() -> guard.check(function("students.updateStudentName"), teacher, arguments)).hasMessageContaining("404");
        verify(jdbc, times(2)).queryForObject(sql, Long.class, studentId, teacher.id());
    }

    @Test
    void everyNewGroupMemberIsScopedToTheEffectiveTeacher() {
        UUID owned = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        String sql = "SELECT count(*) FROM users WHERE id=? AND teacher_id=? AND role='STUDENT'";
        when(jdbc.queryForObject(sql, Long.class, owned, teacher.id())).thenReturn(1L);
        when(jdbc.queryForObject(sql, Long.class, foreign, teacher.id())).thenReturn(0L);
        assertThatThrownBy(() -> guard.check(function("groups.create"), teacher,
                Map.of("body", Map.of("name", "Group", "studentIds", java.util.List.of(owned.toString(), foreign.toString())))))
                .hasMessageContaining("404");
    }

    @Test
    void onlineClassUsesCanonicalHostAccessService() {
        UUID classId = UUID.randomUUID();
        when(gate.isEnabledForTeacher(teacher.id())).thenReturn(true);
        guard.check(function("online_classes.end"), teacher, Map.of("classId", classId.toString()));
        verify(classes).requireHost(teacher, classId);
    }

    @Test
    void archivedTransferredLessonHistoryUsesCurrentStudentOwnershipAndDedicatedHistoryPolicy() {
        UUID studentId = UUID.randomUUID();
        UUID formerTeacher = UUID.randomUUID();
        String sql = "SELECT count(*) FROM users WHERE id=? AND teacher_id=? AND role='STUDENT'";
        when(jdbc.queryForObject(sql, Long.class, studentId, teacher.id())).thenReturn(1L);
        guard.check(function("lesson_history.detail"), teacher,
                Map.of("studentId", studentId.toString(), "sourceTeacherId", formerTeacher.toString(), "eventId", "old-lesson"));
        verify(jdbc).queryForObject(sql, Long.class, studentId, teacher.id());
        verifyNoInteractions(lessons);
    }

    private static McpSiteFunctionRegistry.Function function(String id) {
        return McpSiteFunctionRegistry.functions().stream().filter(function -> function.id().equals(id)).findFirst().orElseThrow();
    }
}
