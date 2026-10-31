package com.mcschool.flashcard.students;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.lessons.ManualLessonService;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.users.UserStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StudentStatusControllerTest {

    private final UserRepository users = mock(UserRepository.class);
    private final ManualLessonService lessons = mock(ManualLessonService.class);
    private final StudentStatusController controller = new StudentStatusController(users, lessons);

    @Test
    void deactivationCancelsFutureLocalLessonsAndKeepsHistory() {
        User teacher = User.activeTeacher("Teacher", "teacher@test.local", "teacher", "unused");
        User student = User.managedStudent("Student", teacher);
        AuthenticatedUser caller = new AuthenticatedUser(teacher.getId(), teacher.getEmail(), Role.TEACHER);
        when(users.findById(student.getId())).thenReturn(Optional.of(student));

        controller.deactivate(caller, student.getId());

        verify(lessons).cancelFutureLocalLessonsForStudent(caller, student.getId());
        assertThat(student.getStatus()).isEqualTo(UserStatus.INACTIVE);
    }
}
