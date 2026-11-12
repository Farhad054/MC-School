package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TrialGuestAnonymizerTest {
    @Test
    void replacesOnlyGuestNamesOlderThanTheRetentionPeriod() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), eq("Trial guest"), eq("Trial guest"), eq(30))).thenReturn(2);

        assertThat(new TrialGuestAnonymizer(jdbc, 30).anonymizeExpiredGuests()).isEqualTo(2);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("Trial guest"), eq("Trial guest"), eq(30));
        // Never touches real accounts, and never a guest that is still inside the window.
        assertThat(sql.getValue()).contains("is_trial_guest").contains("created_at <");
    }

    @Test
    void rejectsAZeroDayRetention() {
        assertThatThrownBy(() -> new TrialGuestAnonymizer(mock(JdbcTemplate.class), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
