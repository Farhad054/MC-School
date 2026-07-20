package com.mcschool.flashcard.users;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the very first admin account at startup, because the PRD has no
 * self-registration: the admin creates teachers, teachers create students.
 * Runs only when ADMIN_EMAIL and ADMIN_PASSWORD are set and no admin exists,
 * so it is a no-op on every later start.
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminAccountInitializer(UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Value("${app.bootstrap.admin-email}") String adminEmail,
                                   @Value("${app.bootstrap.admin-password}") String adminPassword,
                                   @Value("${app.bootstrap.admin-name}") String adminName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.info("ADMIN_EMAIL / ADMIN_PASSWORD not set — skipping initial admin creation");
            return;
        }
        if (userRepository.existsByRole(Role.ADMIN)) {
            return;
        }
        User admin = User.bootstrapAdmin(adminName, adminEmail.trim().toLowerCase(),
                passwordEncoder.encode(adminPassword));
        userRepository.save(admin);
        log.info("Created initial admin account for {}", admin.getEmail());
    }
}
