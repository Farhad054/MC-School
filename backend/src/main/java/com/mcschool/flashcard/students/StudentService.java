package com.mcschool.flashcard.students;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.notifications.NotificationService;
import com.mcschool.flashcard.students.dto.CreateStudentRequest;
import com.mcschool.flashcard.students.dto.StudentInvitationResponse;
import com.mcschool.flashcard.users.Invitations;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Student accounts are always owned by the teacher who created them; every
 * operation here is scoped to the calling teacher.
 */
@Service
public class StudentService {

    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public StudentService(UserRepository userRepository, NotificationService notificationService) {
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public StudentInvitationResponse createStudent(AuthenticatedUser teacher, CreateStudentRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists");
        }
        User teacherEntity = userRepository.findById(teacher.id())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher account no longer exists"));
        String token = Invitations.newToken();
        Instant expiresAt = Invitations.expiry(Instant.now());
        User student = userRepository.save(
                User.invitedStudent(request.fullName().trim(), email, teacherEntity, token, expiresAt));
        notificationService.sendInvitation(student, token);
        return new StudentInvitationResponse(UserResponse.from(student), token, expiresAt);
    }

    /** Lists only the calling teacher's own students — teachers never see each other's students. */
    @Transactional(readOnly = true)
    public List<UserResponse> listStudents(AuthenticatedUser teacher) {
        return userRepository.findAllByTeacherIdOrderByFullNameAsc(teacher.id()).stream()
                .map(UserResponse::from)
                .toList();
    }
}
