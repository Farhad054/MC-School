package com.mcschool.flashcard.study;

import com.mcschool.flashcard.cards.CardStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The simplified spaced-repetition schedule.
 *
 * <p>After a card is answered correctly on the first attempt in a scheduled
 * session, its repetition number is advanced and its next review is booked using
 * fixed intervals:
 *
 * <pre>
 *   repetition 1  -> next review in  1 day
 *   repetition 2  -> next review in  2 days
 *   repetition 3  -> next review in  4 days
 *   repetition 4  -> next review in  7 days
 *   repetition 5  -> next review in 14 days
 *   repetition 6  -> next review in 30 days
 * </pre>
 *
 * <p>After the 30-day interval is reached, the interval is capped there. If the
 * first attempt is wrong, the card is scheduled for tomorrow and its repetition
 * streak is fully reset to 0. The student must then build the sequence again from
 * the beginning: 1 -> 2 -> 4 -> 7 -> 14 -> 30 days. The wrong card is still
 * retried within the same session by {@link StudySessionItem}; voluntary practice
 * sessions never call this scheduler.
 */
@Component
public class Sm2Scheduler {

    /** Days until the next review after repetitions 1 through 6 respectively. */
    static final int[] INTERVAL_DAYS = {1, 2, 4, 7, 14, 30};

    /** Highest repetition number supported by the current schedule. */
    static final int MAX_REPETITION = INTERVAL_DAYS.length;

    /** The new spaced-repetition state to store on a card. */
    public record Scheduling(int repetitionNumber, LocalDate dueDate, CardStatus status) {
    }

    /**
     * Computes the next state for a card that was just reviewed in a scheduled
     * session.
     *
     * @param currentRepetition the card's repetition number before this review
     * @param firstAttemptCorrect whether the first answer in this session was correct
     * @param today the day the session completed
     */
    public Scheduling afterReview(int currentRepetition, boolean firstAttemptCorrect, LocalDate today) {
        if (!firstAttemptCorrect) {
            return new Scheduling(0, today.plusDays(1), CardStatus.ACTIVE);
        }

        int newRepetition = Math.min(currentRepetition + 1, MAX_REPETITION);
        int intervalDays = INTERVAL_DAYS[newRepetition - 1];
        return new Scheduling(newRepetition, today.plusDays(intervalDays), CardStatus.ACTIVE);
    }
}
