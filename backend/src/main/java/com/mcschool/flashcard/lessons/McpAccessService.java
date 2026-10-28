package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserStatus;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Current OAuth identity and explicit teacher delegation for MCP website adapters. */
@Service
public class McpAccessService {
    private final JdbcTemplate jdbc;

    public McpAccessService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Account(UUID id, String email, Role role, UserStatus status, boolean archived) {}

    public AuthenticatedUser requireActor(User oauthUser) {
        if (oauthUser == null || oauthUser.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "School OAuth sign-in is required");
        }
        return requireCurrentActor(new AuthenticatedUser(oauthUser.getId(), oauthUser.getEmail(), oauthUser.getRole()));
    }

    public AuthenticatedUser requireCurrentActor(AuthenticatedUser actor) {
        if (actor == null || actor.id() == null) throw denied();
        Account current = current(actor.id());
        if (!active(current) || current.role() != actor.role()
                || (current.role() != Role.ADMIN && current.role() != Role.TEACHER)) throw denied();
        return principal(current);
    }

    public AuthenticatedUser requireTeacher(AuthenticatedUser actor, UUID selectedTeacherId) {
        AuthenticatedUser current = requireCurrentActor(actor);
        UUID teacherId = selectedTeacherId;
        if (current.role() == Role.TEACHER) {
            if (teacherId != null && !teacherId.equals(current.id())) throw denied();
            teacherId = current.id();
        } else if (teacherId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An explicit teacherId is required for an administrator");
        }
        Account teacher = current(teacherId);
        if (!active(teacher) || teacher.role() != Role.TEACHER) throw denied();
        return principal(teacher);
    }

    /** The original actor remains available to audit consumers while method security sees the selected teacher. */
    public record DelegationAudit(UUID actorId, Role actorRole, UUID effectiveUserId) {}

    public <T> T asTeacher(AuthenticatedUser actor, AuthenticatedUser teacher, Supplier<T> work) {
        if (teacher == null) throw denied();
        AuthenticatedUser currentActor = requireCurrentActor(actor);
        AuthenticatedUser currentTeacher = requireTeacher(currentActor, teacher.id());
        return scoped(currentActor, currentTeacher, work);
    }

    public <T> T asActor(AuthenticatedUser actor, Supplier<T> work) {
        AuthenticatedUser current = requireCurrentActor(actor);
        return scoped(current, current, work);
    }

    private <T> T scoped(AuthenticatedUser actor, AuthenticatedUser effective, Supplier<T> work) {
        SecurityContext original = SecurityContextHolder.getContext();
        SecurityContext delegated = SecurityContextHolder.createEmptyContext();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(effective, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + effective.role().name())));
        authentication.setDetails(new DelegationAudit(actor.id(), actor.role(), effective.id()));
        delegated.setAuthentication(authentication);
        try {
            SecurityContextHolder.setContext(delegated);
            return work.get();
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }

    // Scalar SQL intentionally bypasses the persistence context: a receipt lock
    // may have waited while a different transaction archived or changed a user.
    private Account current(UUID id) {
        return jdbc.query("SELECT id, email, role, status, archived FROM users WHERE id = ?",
                (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("email"),
                        Role.valueOf(rs.getString("role")), UserStatus.valueOf(rs.getString("status")), rs.getBoolean("archived")),
                id).stream().findFirst().orElseThrow(McpAccessService::denied);
    }

    private boolean active(Account user) { return user.status() == UserStatus.ACTIVE && !user.archived(); }
    private static AuthenticatedUser principal(Account user) {
        return new AuthenticatedUser(user.id(), user.email(), user.role());
    }
    private static ResponseStatusException denied() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Active school OAuth administrator or teacher access is required");
    }
}
