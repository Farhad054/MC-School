package com.mcschool.flashcard.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcschool.flashcard.cards.CardStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Sm2SchedulerTest {

    private final Sm2Scheduler scheduler = new Sm2Scheduler();
    private final LocalDate today = LocalDate.of(2026, 7, 20);

    @Test
    void firstSuccessfulReviewSchedulesInThreeDays() {
        Sm2Scheduler.Scheduling result = scheduler.afterSuccessfulReview(0, today);

        assertThat(result.repetitionNumber()).isEqualTo(1);
        assertThat(result.dueDate()).isEqualTo(today.plusDays(3));
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void secondSuccessfulReviewSchedulesInSevenDays() {
        Sm2Scheduler.Scheduling result = scheduler.afterSuccessfulReview(1, today);

        assertThat(result.repetitionNumber()).isEqualTo(2);
        assertThat(result.dueDate()).isEqualTo(today.plusDays(7));
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void thirdSuccessfulReviewSchedulesInTwentyOneDaysAndMarksLearned() {
        Sm2Scheduler.Scheduling result = scheduler.afterSuccessfulReview(2, today);

        assertThat(result.repetitionNumber()).isEqualTo(3);
        assertThat(result.dueDate()).isEqualTo(today.plusDays(21));
        assertThat(result.status()).isEqualTo(CardStatus.LEARNED);
    }
}
