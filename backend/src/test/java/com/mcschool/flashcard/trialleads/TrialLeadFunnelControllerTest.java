package com.mcschool.flashcard.trialleads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.mcschool.flashcard.notifications.PushSubscriptionRepository;
import com.mcschool.flashcard.notifications.WebPushService;
import com.mcschool.flashcard.users.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class TrialLeadFunnelControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MetaFunnelService meta = mock(MetaFunnelService.class);
    private final TrialLeadController controller = new TrialLeadController(jdbc,
            mock(UserRepository.class), mock(PushSubscriptionRepository.class), mock(WebPushService.class), meta);

    @Test
    void changingStatusUsesAtomicStatusAndOutboxWrite() {
        UUID id = UUID.randomUUID();
        when(meta.updateLeadStatus(eq(id), anyString())).thenReturn(1);
        assertThat(controller.setStatus(id, Map.of("status", "BOOKED"))).containsEntry("status", "BOOKED");
        controller.setStatus(id, Map.of("status", "QUALIFIED_LEAD"));
        controller.setStatus(id, Map.of("status", "CONTRACT"));
        verify(meta).updateLeadStatus(id, "BOOKED");
        verify(meta).updateLeadStatus(id, "QUALIFIED_LEAD");
        verify(meta).updateLeadStatus(id, "CONTRACT");
        verifyNoMoreInteractions(meta);
        verifyNoInteractions(jdbc);
    }

    @Test
    void unchangedStatusDoesNotRecordAnotherEvent() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id))).thenReturn(1);
        assertThat(controller.setStatus(id, Map.of("status", "CONTRACT"))).containsEntry("status", "CONTRACT");
        verify(meta).updateLeadStatus(id, "CONTRACT");
        verifyNoMoreInteractions(meta);
    }

    @Test
    void missingLeadAndFailedDatabaseWriteCannotEmitEvents() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(id))).thenReturn(0);
        assertThatThrownBy(() -> controller.setStatus(id, Map.of("status", "QUALIFIED_LEAD")))
                .isInstanceOf(ResponseStatusException.class);
        when(meta.updateLeadStatus(id, "BOOKED")).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> controller.setStatus(id, Map.of("status", "BOOKED")))
                .isInstanceOf(IllegalStateException.class);
        verify(meta).updateLeadStatus(id, "QUALIFIED_LEAD");
        verify(meta).updateLeadStatus(id, "BOOKED");
        verifyNoMoreInteractions(meta);
    }

    @Test
    void sourceUrlDropsContactQueriesFragmentsAndRejectsUnsafeSchemes() {
        assertThat(TrialLeadController.eventSourceUrl("https://mindcrafti.de/ru/trial?email=parent@example.test#phone"))
                .isEqualTo("https://mindcrafti.de/ru/trial");
        assertThat(TrialLeadController.eventSourceUrl("javascript:alert(1)")).isNull();
        assertThat(TrialLeadController.eventSourceUrl("https://user:password@mindcrafti.de/trial")).isNull();
    }

    @Test
    void attributionIdentifiersAreValidatedWithoutBeingHashedOrInvented() {
        String fbp = "fb.1.1793872800000.123456789";
        String fbc = "fb.1.1793872800000.real-click-id";
        assertThat(TrialLeadController.metaIdentifier(fbp, false)).isEqualTo(fbp);
        assertThat(TrialLeadController.metaIdentifier(fbc, true)).isEqualTo(fbc);
        assertThat(TrialLeadController.metaIdentifier("not-a-meta-cookie", false)).isNull();
        assertThat(TrialLeadController.metaIdentifier("fb.1.1793872800000.bad\nvalue", true)).isNull();
    }
}
