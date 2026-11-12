package com.mcschool.flashcard.auth;

import com.mcschool.flashcard.auth.dto.ActivateAccountRequest;
import com.mcschool.flashcard.auth.dto.AuthResponse;
import com.mcschool.flashcard.auth.dto.LoginRequest;
import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.common.InvalidInvitationException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptGuard loginAttempts = new LoginAttemptGuard();
    private volatile String decoyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String identifier = request.email().trim();
        if (loginAttempts.locked(identifier)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts. Try again in a few minutes.");
        }
        User user = userRepository.findByEmail(identifier.toLowerCase(Locale.ROOT))
                .or(() -> userRepository.findByUsernameIgnoreCase(identifier))
                .orElse(null);
        // Always spend one hash comparison so response time does not reveal
        // whether the login exists.
        String hash = user == null || user.getPasswordHash() == null ? decoyHash() : user.getPasswordHash();
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);
        if (user == null || user.isArchived() || user.getPasswordHash() == null || !passwordOk) {
            loginAttempts.failed(identifier);
            throw invalidCredentials();
        }
        loginAttempts.succeeded(identifier);
        return issueToken(user);
    }

    private String decoyHash() {
        String hash = decoyPasswordHash;
        if (hash == null) decoyPasswordHash = hash = passwordEncoder.encode("decoy-" + UUID.randomUUID());
        return hash;
    }

    /**
     * Completes an invitation. Students can activate with the school username only;
     * teachers and parents still require an email address.
     */
    @Transactional
    public AuthResponse activateAccount(ActivateAccountRequest request) {
        User user = userRepository.findByInvitationToken(request.invitationToken())
                .orElseThrow(() -> new InvalidInvitationException("Invitation is invalid or already used"));
        if (user.isArchived()) {
            throw new InvalidInvitationException("Invitation is invalid or already used");
        }
        if (user.isInvitationExpired(Instant.now())) {
            throw new InvalidInvitationException("Invitation has expired — ask for a new invitation");
        }

        boolean hasEmail = request.email() != null && !request.email().isBlank();
        if (!hasEmail && user.getRole() != Role.STUDENT) {
            throw new InvalidInvitationException("Email is required to activate this account");
        }

        if (hasEmail) {
            String email = normalizeEmail(request.email());
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                if (userRepository.existsByEmail(email)) {
                    throw new ConflictException("An account with this email already exists");
                }
                user.assignEmail(email);
            } else if (!user.getEmail().equalsIgnoreCase(email)) {
                throw new InvalidInvitationException("Email does not match this invitation");
            }
        }

        if (user.getRole() == Role.STUDENT && (user.getUsername() == null || user.getUsername().isBlank())) {
            throw new InvalidInvitationException("Student login is not ready yet — ask the teacher to open the student list once");
        }

        user.activate(passwordEncoder.encode(request.password()));
        return issueToken(user);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AuthenticatedUser caller) {
        return userRepository.findById(caller.id())
                .filter(user -> !user.isArchived())
                .map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account no longer exists"));
    }

    @Transactional(readOnly = true)
    public AuthResponse impersonateTeacher(UUID teacherId) {
        return impersonate(teacherId, Role.TEACHER, "Teacher");
    }

    @Transactional(readOnly = true)
    public AuthResponse impersonateStudent(UUID studentId) {
        return impersonate(studentId, Role.STUDENT, "Student");
    }

    @Transactional(readOnly = true)
    public AuthResponse impersonateParent(UUID parentId) {
        return impersonate(parentId, Role.PARENT, "Parent");
    }

    private AuthResponse impersonate(UUID userId, Role expectedRole, String accountLabel) {
        User user = userRepository.findById(userId)
                .filter(candidate -> !candidate.isArchived())
                .filter(candidate -> candidate.getRole() == expectedRole)
                .orElseThrow(() -> new ResourceNotFoundException(accountLabel + " account not found"));
        return issueToken(user);
    }

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user);
        return AuthResponse.bearer(token, jwtService.expiresAt(token), UserResponse.from(user));
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid login or password");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
