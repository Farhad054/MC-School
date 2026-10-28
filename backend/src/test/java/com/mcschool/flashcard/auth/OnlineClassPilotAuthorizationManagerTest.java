package com.mcschool.flashcard.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class OnlineClassPilotAuthorizationManagerTest {
    private static final UUID FIRST_TEACHER_ID = UUID.fromString("14e3c7c1-1fc8-41bd-858a-6c20efdd957a");
    private static final UUID SECOND_TEACHER_ID = UUID.fromString("0bc65a88-c98f-4598-a7bf-65ed2c5cd443");
    private static final UUID STUDENT_ID = UUID.fromString("8c72f977-2e57-4b3c-8d94-d1b0e3dfdc98");
    private UserRepository users;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
    }

    @Test
    void disabledFeatureBlocksAccountAndPhoneAccessWithoutLookingUpUsers() {
        var guard = new OnlineClassPilotAuthorizationManager(users, false);
        assertFalse(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.TEACHER)));
        assertFalse(guard.isAllowed(auth(STUDENT_ID, Role.STUDENT)));
        assertFalse(guard.isEnabledForTeacher(FIRST_TEACHER_ID));
        verifyNoInteractions(users);
    }

    @Test
    void allActiveTeachersMayHostWithoutAnOwnerUuidAllowlist() {
        var guard = enabledGate();
        allowTeacher(FIRST_TEACHER_ID);
        allowTeacher(SECOND_TEACHER_ID);
        assertTrue(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.TEACHER)));
        assertTrue(guard.isAllowed(auth(SECOND_TEACHER_ID, Role.TEACHER)));
        assertTrue(guard.isEnabledForTeacher(SECOND_TEACHER_ID));
    }

    @Test
    void inactiveArchivedOrMissingTeacherCannotGetAccountOrPhoneAccess() {
        var guard = enabledGate();
        // The active/nonarchived database predicate excludes all three cases.
        assertFalse(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.TEACHER)));
        assertFalse(guard.isEnabledForTeacher(FIRST_TEACHER_ID));
        verify(users, times(2)).existsByIdAndRoleAndStatusAndArchivedFalse(
                FIRST_TEACHER_ID, Role.TEACHER, UserStatus.ACTIVE);
    }

    @Test
    void studentsNeedBothTheirOwnAndAssignedTeachersCurrentEligibility() {
        var guard = enabledGate();
        when(users.existsActiveStudentWithActiveTeacher(STUDENT_ID)).thenReturn(true);
        assertTrue(guard.isAllowed(auth(STUDENT_ID, Role.STUDENT)));

        // Losing the teacher assignment, either account's activation, or either
        // account being archived must be reflected on the next request.
        when(users.existsActiveStudentWithActiveTeacher(STUDENT_ID)).thenReturn(false);
        assertFalse(guard.isAllowed(auth(STUDENT_ID, Role.STUDENT)));
    }

    @Test
    void aStudentIdPresentedAsTeacherDoesNotGainHostOrPhoneAccess() {
        var guard = enabledGate();
        when(users.existsActiveStudentWithActiveTeacher(STUDENT_ID)).thenReturn(true);
        assertFalse(guard.isAllowed(auth(STUDENT_ID, Role.TEACHER)));
        assertFalse(guard.isEnabledForTeacher(STUDENT_ID));
        verify(users, times(2)).existsByIdAndRoleAndStatusAndArchivedFalse(
                STUDENT_ID, Role.TEACHER, UserStatus.ACTIVE);
    }

    @Test
    void aTeacherIdPresentedAsStudentDoesNotGainStudentEligibility() {
        var guard = enabledGate();
        allowTeacher(FIRST_TEACHER_ID);
        assertFalse(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.STUDENT)));
        verify(users).existsActiveStudentWithActiveTeacher(FIRST_TEACHER_ID);
    }

    @Test
    void rejectsParentsAdminsAndAnonymousWithoutDatabaseQueries() {
        var guard = enabledGate();
        assertFalse(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.ADMIN)));
        assertFalse(guard.isAllowed(auth(FIRST_TEACHER_ID, Role.PARENT)));
        assertFalse(guard.isAllowed(null));
        assertFalse(guard.isAllowed(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")))));
        assertFalse(guard.isAllowed(UsernamePasswordAuthenticationToken.unauthenticated(
                new AuthenticatedUser(FIRST_TEACHER_ID, null, Role.TEACHER), null)));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsUnexpectedPrincipalAndNullIdentifiers() {
        var guard = enabledGate();
        assertFalse(guard.isAllowed(UsernamePasswordAuthenticationToken.authenticated("fake-client-id", null, List.of())));
        assertFalse(guard.isAllowed(auth(null, Role.TEACHER)));
        assertFalse(guard.isAllowed(auth(null, Role.STUDENT)));
        assertFalse(guard.isEnabledForTeacher(null));
        verifyNoInteractions(users);
    }

    private void allowTeacher(UUID id) {
        when(users.existsByIdAndRoleAndStatusAndArchivedFalse(id, Role.TEACHER, UserStatus.ACTIVE)).thenReturn(true);
    }

    private OnlineClassPilotAuthorizationManager enabledGate() {
        return new OnlineClassPilotAuthorizationManager(users, true);
    }

    private Authentication auth(UUID userId, Role role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(userId, null, role), null, List.of());
    }
}
