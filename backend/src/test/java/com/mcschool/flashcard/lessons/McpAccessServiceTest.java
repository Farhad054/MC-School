package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class McpAccessServiceTest {
    private final User admin = User.bootstrapAdmin("Administrator", "admin@test.local", "hash");
    private final User teacher = User.activeTeacher("Teacher", "teacher@test.local", "teacher", "hash");
    private final User secondTeacher = User.activeTeacher("Other", "other@test.local", "other", "hash");
    private final Map<UUID, McpAccessService.Account> rows = new HashMap<>();
    private McpAccessService access;

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(UUID.class))).thenAnswer(invocation -> {
            var row = rows.get(invocation.getArgument(2, UUID.class));
            return row == null ? List.of() : List.of(row);
        });
        access = new McpAccessService(jdbc);
        for (User user : List.of(admin, teacher, secondTeacher)) row(user, user.getRole(), UserStatus.ACTIVE, false);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void oauthIsRequiredAndCurrentDatabaseRoleCannotBeForged() {
        assertDenied(() -> access.requireActor(null), HttpStatus.UNAUTHORIZED);
        User forged = mock(User.class);
        when(forged.getId()).thenReturn(teacher.getId());
        when(forged.getRole()).thenReturn(Role.ADMIN);
        assertDenied(() -> access.requireActor(forged), HttpStatus.FORBIDDEN);
        assertThat(access.requireActor(admin).role()).isEqualTo(Role.ADMIN);
        assertThat(access.requireActor(teacher).role()).isEqualTo(Role.TEACHER);
    }

    @Test
    void parentStudentInactiveArchivedAndDeletedActorsAreDenied() {
        for (Role role : List.of(Role.PARENT, Role.STUDENT)) {
            row(teacher, role, UserStatus.ACTIVE, false);
            assertDenied(() -> access.requireCurrentActor(new AuthenticatedUser(teacher.getId(), null, role)), HttpStatus.FORBIDDEN);
        }
        for (UserStatus status : List.of(UserStatus.INVITED, UserStatus.INACTIVE)) {
            row(teacher, Role.TEACHER, status, false);
            assertDenied(() -> access.requireActor(teacher), HttpStatus.FORBIDDEN);
        }
        row(teacher, Role.TEACHER, UserStatus.ACTIVE, true);
        assertDenied(() -> access.requireActor(teacher), HttpStatus.FORBIDDEN);
        rows.remove(teacher.getId());
        assertDenied(() -> access.requireActor(teacher), HttpStatus.FORBIDDEN);
    }

    @Test
    void teachersCannotSelectAnotherTeacherAndAdminsMustSelectAnActiveTeacherExplicitly() {
        var teacherActor = access.requireActor(teacher);
        var adminActor = access.requireActor(admin);
        assertThat(access.requireTeacher(teacherActor, null).id()).isEqualTo(teacher.getId());
        assertDenied(() -> access.requireTeacher(teacherActor, secondTeacher.getId()), HttpStatus.FORBIDDEN);
        assertDenied(() -> access.requireTeacher(adminActor, null), HttpStatus.BAD_REQUEST);
        assertDenied(() -> access.requireTeacher(adminActor, admin.getId()), HttpStatus.FORBIDDEN);
        assertThat(access.requireTeacher(adminActor, secondTeacher.getId()).id()).isEqualTo(secondTeacher.getId());
        row(secondTeacher, Role.TEACHER, UserStatus.INACTIVE, false);
        assertDenied(() -> access.requireTeacher(adminActor, secondTeacher.getId()), HttpStatus.FORBIDDEN);
    }

    @Test
    void delegationUsesTeacherAuthorityPreservesActorForAuditAndRestoresOriginalContextOnFailure() {
        var actor = access.requireActor(admin);
        var selected = access.requireTeacher(actor, teacher.getId());
        var original = SecurityContextHolder.createEmptyContext();
        original.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
        SecurityContextHolder.setContext(original);
        assertThatThrownBy(() -> access.asTeacher(actor, selected, () -> {
            var delegated = SecurityContextHolder.getContext().getAuthentication();
            assertThat(delegated.getPrincipal()).isEqualTo(selected);
            assertThat(delegated.getAuthorities()).extracting("authority").containsExactly("ROLE_TEACHER");
            assertThat(delegated.getDetails()).isEqualTo(new McpAccessService.DelegationAudit(admin.getId(), Role.ADMIN, teacher.getId()));
            throw new IllegalStateException("domain failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext()).isSameAs(original);
    }

    @Test
    void actorScopePreservesAdminAuthorityAndRechecksChangedAccounts() {
        var actor = access.requireActor(admin);
        assertThat(access.asActor(actor, () -> SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().iterator().next().getAuthority())).isEqualTo("ROLE_ADMIN");
        row(admin, Role.ADMIN, UserStatus.ACTIVE, true);
        assertDenied(() -> access.asActor(actor, () -> "must not run"), HttpStatus.FORBIDDEN);
    }

    private void row(User user, Role role, UserStatus status, boolean archived) {
        rows.put(user.getId(), new McpAccessService.Account(user.getId(), user.getEmail(), role, status, archived));
    }

    private void assertDenied(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
