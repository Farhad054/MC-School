package com.mcschool.flashcard.homeworks;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcschool.flashcard.homeworks.dto.HomeworkResponse;
import com.mcschool.flashcard.users.User;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HomeworkResponseTest {

    private final User teacher = User.invitedTeacher("Teacher", "teacher@test.local",
            "teacher-token", Instant.now().plusSeconds(3600));
    private final User student = User.invitedStudent("Student", "student@test.local", teacher,
            "student-token", Instant.now().plusSeconds(3600));

    @Test
    void futureHomeworkIsPending() {
        Homework homework = Homework.create(student, LocalDate.now().plusDays(1));
        HomeworkResponse response = HomeworkResponse.from(homework, Map.of(homework.getId(),
                new HomeworkStats(homework.getId(), 3, 3, 0, 0)));

        assertThat(response.status()).isEqualTo(HomeworkStatus.PENDING);
    }

    @Test
    void startedHomeworkIsActiveUntilEveryCardIsLearned() {
        Homework homework = Homework.create(student, LocalDate.now());
        HomeworkResponse response = HomeworkResponse.from(homework, Map.of(homework.getId(),
                new HomeworkStats(homework.getId(), 3, 1, 1, 1)));

        assertThat(response.status()).isEqualTo(HomeworkStatus.ACTIVE);
    }

    @Test
    void allLearnedHomeworkIsCompletedEvenWhenStartDateIsFuture() {
        Homework homework = Homework.create(student, LocalDate.now().plusDays(1));
        UUID homeworkId = homework.getId();
        HomeworkResponse response = HomeworkResponse.from(homework, Map.of(homeworkId,
                new HomeworkStats(homeworkId, 3, 0, 0, 3)));

        assertThat(response.status()).isEqualTo(HomeworkStatus.COMPLETED);
    }
}
