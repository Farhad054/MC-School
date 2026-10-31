package com.mcschool.flashcard.auth;

import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserStatus;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Account eligibility gate for the Mindcrafti Online classroom.
 *
 * Uses the server-verified JWT principal and current database account state,
 * never a user id or role provided by the browser. The feature remains
 * deny-by-default until ONLINE_CLASSES_ENABLED is configured.
 *
 * Active teachers and active students of active teachers may use the classroom.
 * The online class service must still verify the particular class membership;
 * this gate is an additional restriction, not a replacement for that check.
 */
@Component
public class OnlineClassPilotAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final UserRepository userRepository;
    private final boolean enabled;

    public OnlineClassPilotAuthorizationManager(
            UserRepository userRepository,
            @Value("${app.online-classes.enabled:false}") boolean enabled) {
        this.userRepository = userRepository;
        this.enabled = enabled;
    }

    @Override
    public AuthorizationDecision authorize(
            Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        return new AuthorizationDecision(isAllowed(authentication.get()));
    }

    boolean isAllowed(Authentication authentication) {
        if (!enabled || authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser caller)) {
            return false;
        }

        if (caller.role() == Role.TEACHER) {
            return isEnabledForTeacher(caller.id());
        }

        // Students may join their assigned teacher's classroom by link.
        // In addition, the room's own access service enforces class enrollment.
        if (caller.role() == Role.STUDENT) {
            return caller.id() != null && userRepository.existsActiveStudentWithActiveTeacher(caller.id());
        }

        // Do not open classroom APIs to administrators or parents by role alone.
        return false;
    }

    /** Also checked by credential-scoped phone endpoints, which have no account JWT. */
    public boolean isEnabledForTeacher(UUID teacherId) {
        return enabled && teacherId != null
                && userRepository.existsByIdAndRoleAndStatusAndArchivedFalse(
                        teacherId, Role.TEACHER, UserStatus.ACTIVE);
    }
}
