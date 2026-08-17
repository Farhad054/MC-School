package com.mcschool.flashcard.auth;

import com.mcschool.flashcard.auth.dto.ActivateAccountRequest;
import com.mcschool.flashcard.auth.dto.AuthResponse;
import com.mcschool.flashcard.auth.dto.LoginRequest;
import com.mcschool.flashcard.common.InvalidInvitationException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;
import java.util.Locale;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // One generic failure for unknown email, wrong password and
        // not-yet-activated accounts, so the endpoint cannot be used to
        // probe which emails exist.
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(AuthService::invalidCredentials);
        if (user.isArchived()
                || user.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return issueToken(user);
    }

    /**
     * Completes an invitation: the invited teacher or student sets their
     * password and is logged in right away.
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

    private AuthResponse issueToken(User user) {
        String token = jwtService.generateToken(user);
        return AuthResponse.bearer(token, jwtService.expiresAt(token), UserResponse.from(user));
    }

    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid email or password");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
