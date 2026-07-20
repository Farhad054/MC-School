package com.mcschool.flashcard.study;

import com.mcschool.flashcard.cards.CardStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * The simplified spaced-repetition schedule from PRD 4.4.
 *
 * <p>After a card is answered correctly in a <b>scheduled</b> session its
 * repetition number is advanced and its next review is booked using fixed
 * intervals:
 *
 * <pre>
 *   repetition 1  → next review in  3 days
 *   repetition 2  → next review in  7 days
 *   repetition 3  → next review in 21 days
 * </pre>
 *
 * <p>A card is considered <b>learned after 3 successful repetitions</b>
 * (PRD 4.4). When it reaches repetition 3 its status becomes {@link CardStatus#LEARNED}
 * and it is no longer surfaced in mandatory sessions. The 21-day due date is still
 * recorded so a future "maintenance review" feature (v1.5) can use it, but the MVP
 * treats a learned card as finished.
 *
 * <p>Mistakes never reset the interval: a wrong card is simply retried within the
 * same session and the schedule only advances once the session completes
 * successfully. Voluntary practice sessions never call this scheduler.
 *
 * <p>All rules live here and are covered by unit tests, so the intervals or the
 * "learned" threshold can be changed in one place if the team refines them.
 */
@Component
public class Sm2Scheduler {

    /** Days until the next review after repetitions 1, 2 and 3 respectively. */
    static final int[] INTERVAL_DAYS = {3, 7, 21};

    /** A card is learned once it has been successfully reviewed this many times. */
    static final int REPETITIONS_TO_LEARN = INTERVAL_DAYS.length;

    /** The new spaced-repetition state to store on a card. */
    public record Scheduling(int repetitionNumber, LocalDate dueDate, CardStatus status) {
    }

    /**
     * Computes the next state for a card that was just reviewed successfully in a
     * scheduled session.
     *
     * @param currentRepetition the card's repetition number before this review (0..2)
     * @param today             the day the session completed
     */
    public Scheduling afterSuccessfulReview(int currentRepetition, LocalDate today) {
        int newRepetition = currentRepetition + 1;
        int intervalDays = INTERVAL_DAYS[Math.min(newRepetition, REPETITIONS_TO_LEARN) - 1];
        CardStatus status = newRepetition >= REPETITIONS_TO_LEARN ? CardStatus.LEARNED : CardStatus.ACTIVE;
        return new Scheduling(newRepetition, today.plusDays(intervalDays), status);
    }
}
