package com.mcschool.flashcard.teachers;

import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.teachers.dto.CreateTeacherRequest;
import com.mcschool.flashcard.teachers.dto.TeacherInvitationResponse;
import com.mcschool.flashcard.users.Invitations;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeacherService {

    private final UserRepository userRepository;

    public TeacherService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public TeacherInvitationResponse createTeacher(CreateTeacherRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        String token = Invitations.newToken();
        Instant expiresAt = Invitations.expiry(Instant.now());
        User teacher = userRepository.save(
                User.invitedTeacher(request.fullName().trim(), email, token, expiresAt));
        return new TeacherInvitationResponse(UserResponse.from(teacher), token, expiresAt);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listTeachers() {
        return userRepository.findAllByRoleOrderByFullNameAsc(Role.TEACHER).stream()
                .map(UserResponse::from)
                .toList();
    }
}
