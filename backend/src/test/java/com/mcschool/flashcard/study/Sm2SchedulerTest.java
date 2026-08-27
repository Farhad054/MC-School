package com.mcschool.flashcard.study;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcschool.flashcard.cards.CardStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class Sm2SchedulerTest {

    private final Sm2Scheduler scheduler = new Sm2Scheduler();
    private final LocalDate today = LocalDate.of(2026, 7, 20);

    @Test
    void correctFirstAttemptProgressesThroughFixedIntervals() {
        int[] expectedIntervals = {1, 2, 4, 7, 14, 30};

        for (int currentRepetition = 0; currentRepetition < expectedIntervals.length; currentRepetition++) {
            Sm2Scheduler.Scheduling result = scheduler.afterReview(currentRepetition, true, today);

            assertThat(result.repetitionNumber()).isEqualTo(currentRepetition + 1);
            assertThat(result.dueDate()).isEqualTo(today.plusDays(expectedIntervals[currentRepetition]));
            assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
        }
    }

    @Test
    void wrongFirstAttemptSchedulesTomorrowAndResetsProgress() {
        Sm2Scheduler.Scheduling result = scheduler.afterReview(3, false, today);

        assertThat(result.repetitionNumber()).isEqualTo(0);
        assertThat(result.dueDate()).isEqualTo(today.plusDays(1));
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void intervalIsCappedAtThirtyDays() {
        Sm2Scheduler.Scheduling result = scheduler.afterReview(6, true, today);

        assertThat(result.repetitionNumber()).isEqualTo(6);
        assertThat(result.dueDate()).isEqualTo(today.plusDays(30));
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
    }
}
