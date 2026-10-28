package com.mcschool.flashcard.homeworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.notifications.PushSubscriptionRepository;
import com.mcschool.flashcard.notifications.WebPushService;
import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HomeworkPdfServiceStudentDownloadTest {

    private final HomeworkRepository homeworkRepository = mock(HomeworkRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final HomeworkPdfService service = new HomeworkPdfService(
            homeworkRepository, userRepository,
            mock(PushSubscriptionRepository.class), mock(WebPushService.class));

    private final UUID studentId = UUID.randomUUID();
    private final User student = mock(User.class);
    private final AuthenticatedUser caller = new AuthenticatedUser(studentId, null, Role.STUDENT);
    private Homework homework;
    private final byte[] original = "%PDF-1.7 original".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        when(student.getId()).thenReturn(studentId);
        when(student.getRole()).thenReturn(Role.STUDENT);
        when(userRepository.findById(studentId)).thenReturn(Optional.of(student));
        homework = Homework.create(student, LocalDate.of(2026, 10, 21));
        homework.attachWorksheet("Mathe-Aufgaben.pdf", original, 1);
        when(homeworkRepository.findByIdAndStudentId(homework.getId(), studentId))
                .thenReturn(Optional.of(homework));
    }

    @Test
    void downloadsOriginalPdfEvenAfterHomeworkWasSubmitted() {
        byte[] answer = "%PDF-1.7 student answers".getBytes(StandardCharsets.UTF_8);
        homework.submitWorksheet("answer.pdf", answer, Instant.now());

        HomeworkPdfService.WorksheetDownload result = service.studentWorksheet(caller, homework.getId());

        assertThat(result.bytes()).isEqualTo(original).isNotEqualTo(answer);
        assertThat(result.filename()).isEqualTo("Mathe-Aufgaben.pdf");
    }

    @Test
    void neverDownloadsAnotherStudentsHomework() {
        AuthenticatedUser other = new AuthenticatedUser(UUID.randomUUID(), null, Role.STUDENT);
        assertThatThrownBy(() -> service.studentWorksheet(other, homework.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void preventsArchivedStudentDownload() {
        when(student.isArchived()).thenReturn(true);
        assertThatThrownBy(() -> service.studentWorksheet(caller, homework.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsHomeworkWithoutPdf() {
        homework.attachWorksheet("empty.pdf", new byte[0], 1);
        assertThatThrownBy(() -> service.studentWorksheet(caller, homework.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
