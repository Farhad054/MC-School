package com.mcschool.flashcard.lessons;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A trial-lesson guest is a named visitor with no email, login or profile. The
 * only personal data kept for them is the name they typed. Once the retention
 * period has passed the name is replaced, so transcripts and boards that still
 * point at the guest row show a neutral label and the child's name is gone.
 * The row itself stays, so foreign keys from old lessons remain valid.
 */
@Component
public class TrialGuestAnonymizer {
    static final String ANONYMOUS_NAME = "Trial guest";
    private static final Logger LOG = LoggerFactory.getLogger(TrialGuestAnonymizer.class);

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public TrialGuestAnonymizer(JdbcTemplate jdbc,
                                @Value("${app.trial-guests.retention-days:30}") int retentionDays) {
        if (retentionDays < 1) throw new IllegalArgumentException("Trial guest retention must be at least one day");
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${app.trial-guests.anonymize-cron:0 15 4 * * *}", zone = "Europe/Berlin")
    public int anonymizeExpiredGuests() {
        int changed = jdbc.update("""
                UPDATE users SET full_name = ?
                WHERE is_trial_guest AND full_name <> ?
                  AND created_at < CURRENT_TIMESTAMP - make_interval(days => ?)
                """, ANONYMOUS_NAME, ANONYMOUS_NAME, retentionDays);
        if (changed > 0) LOG.info("Anonymized {} expired trial guest names", changed);
        return changed;
    }
}
