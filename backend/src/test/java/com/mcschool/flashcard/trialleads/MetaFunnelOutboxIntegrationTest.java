package com.mcschool.flashcard.trialleads;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mcschool.flashcard.notifications.PushSubscriptionRepository;
import com.mcschool.flashcard.notifications.WebPushService;
import com.mcschool.flashcard.users.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;

/** Runs against a disposable PostgreSQL container or an explicitly isolated localhost test DB. */
class MetaFunnelOutboxIntegrationTest {
    private static PostgreSQLContainer<?> ownedPostgres;
    private static String jdbcUrl;
    private static String username;
    private static String password;
    private JdbcTemplate jdbc;
    private UUID id;

    @BeforeAll static void isolatedDatabase() {
        String configured = System.getProperty("meta.test.jdbc.url");
        if (configured != null) {
            // schema() deliberately drops its tables. Never accept a remote host,
            // a production database name, credentials in the URL, or URL options.
            URI uri;
            try { uri = URI.create(configured.substring("jdbc:".length())); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid isolated Meta test JDBC URL"); }
            if (!configured.startsWith("jdbc:postgresql://")
                    || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))
                    || !"/meta_funnel_test".equals(uri.getPath()) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() < 1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Meta tests require localhost:<port>/meta_funnel_test with no JDBC URL options");
            }
            jdbcUrl = configured;
            username = "postgres";
            password = "";
            return;
        }
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Requires Docker or -Dmeta.test.jdbc.url pointing to isolated localhost meta_funnel_test");
        ownedPostgres = new PostgreSQLContainer<>("postgres:17-alpine");
        ownedPostgres.start();
        jdbcUrl = ownedPostgres.getJdbcUrl();
        username = ownedPostgres.getUsername();
        password = ownedPostgres.getPassword();
    }

    @AfterAll static void stopOwnedDatabase() {
        if (ownedPostgres != null) ownedPostgres.stop();
    }

    @BeforeEach void schema() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, username, password));
        jdbc.execute("DROP TABLE IF EXISTS trial_lead_meta_conversions; DROP TABLE IF EXISTS trial_leads");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V38__create_trial_leads_table.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V40__add_trial_lead_client_id.sql")));
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V98__meta_funnel_outbox.sql")));
        id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trial_leads(id,tracking_token,phone,status,meta_marketing_consent,meta_event_source_url)
                VALUES(?,?,?,'BOOKED',TRUE,'https://mindcrafti.de/ru/trial')
                """, id, UUID.randomUUID(), "+4915123456789");
    }

    @Test void duplicateRecordingAndConcurrentDeliverySendOneLogicalEvent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var service = service(request -> {
            calls.incrementAndGet(); entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var enqueueA = executor.submit(() -> service.recordSchedule(id));
            var enqueueB = executor.submit(() -> service.recordSchedule(id));
            enqueueA.get(5, TimeUnit.SECONDS); enqueueB.get(5, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM trial_lead_meta_conversions", Integer.class));
            var event = pending();
            var first = executor.submit(() -> service.deliver(event));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.deliver(event));
            second.get(5, TimeUnit.SECONDS);
            finish.countDown(); first.get(5, TimeUnit.SECONDS);
            service.recordSchedule(id);
            service.deliver(event);
            assertEquals(1, calls.get());
            assertEquals("SENT", status());
            assertNotNull(jdbc.queryForObject("SELECT meta_schedule_sent_at FROM trial_leads WHERE id=?", java.sql.Timestamp.class, id));
            assertEquals(event.eventId(), pending().eventId());
            assertEquals(event.occurredAt(), pending().occurredAt());
        } finally { finish.countDown(); executor.shutdownNow(); }
    }

    @Test void timeoutPersistsRetryAndCannotChangeEventIdentityOrOccurrenceTime() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(request -> {
            if (calls.incrementAndGet() == 1) throw new HttpTimeoutException("secret-parent@example.com");
            return new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}");
        });
        service.recordSchedule(id);
        var event = pending();
        service.deliver(event);
        assertEquals("FAILED", status());
        assertEquals("HttpTimeoutException", jdbc.queryForObject("SELECT last_error FROM trial_lead_meta_conversions", String.class));
        assertNull(jdbc.queryForObject("SELECT meta_schedule_sent_at FROM trial_leads", java.sql.Timestamp.class));
        service.deliver(event);
        assertEquals(1, calls.get(), "The retry backoff must prevent an immediate duplicate send");
        jdbc.update("UPDATE trial_lead_meta_conversions SET next_attempt_at=CURRENT_TIMESTAMP");
        service.recordSchedule(id);
        service.deliver(pending());
        assertEquals(2, calls.get());
        assertEquals("SENT", status());
        assertEquals(event.eventId(), pending().eventId());
        assertEquals(event.occurredAt(), pending().occurredAt());
    }

    @Test void recoversAbandonedClaimButExpiresOldConversions() {
        AtomicInteger calls = new AtomicInteger();
        var service = service(request -> {
            calls.incrementAndGet(); return new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}");
        });
        service.recordSchedule(id);
        jdbc.update("UPDATE trial_lead_meta_conversions SET status='SENDING',claimed_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes'");
        service.deliverPending();
        assertEquals("SENT", status());
        assertEquals(1, calls.get());
        service.recordQualifiedLead(id);
        jdbc.update("UPDATE trial_lead_meta_conversions SET occurred_at=CURRENT_TIMESTAMP-INTERVAL '8 days' WHERE event_name='QualifiedLead'");
        service.deliverPending();
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM trial_lead_meta_conversions WHERE event_name='QualifiedLead'", String.class));
        assertEquals(1, calls.get());
    }

    @Test void threeStagesHaveSeparateStableIdsAndNoPermissionMeansNoSend() {
        var service = service(request -> new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}"));
        service.recordSchedule(id); service.recordQualifiedLead(id); service.recordContract(id);
        service.deliverPending();
        assertEquals(3, jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM trial_lead_meta_conversions WHERE status='SENT'", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT meta_purchase_sent_at FROM trial_leads", java.sql.Timestamp.class));
        jdbc.update("DELETE FROM trial_lead_meta_conversions");
        jdbc.update("UPDATE trial_leads SET meta_marketing_consent=FALSE");
        service.recordSchedule(id);
        assertEquals("SUPPRESSED", status());
    }

    @Test void statusTransitionAndOutboxAreAtomicAndSameStatusDoesNotBackfill() {
        var service = service(request -> new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}"));
        assertEquals(0, service.updateLeadStatus(id, "BOOKED"), "Existing legacy status must not create historical conversions");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM trial_lead_meta_conversions", Integer.class));
        assertEquals(1, service.updateLeadStatus(id, "CONTACTED"));
        assertEquals(1, service.updateLeadStatus(id, "QUALIFIED_LEAD"));
        assertEquals(0, service.updateLeadStatus(id, "QUALIFIED_LEAD"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM trial_lead_meta_conversions WHERE event_name='QualifiedLead'", Integer.class));
        assertEquals(1, service.updateLeadStatus(id, "CONTRACT"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM trial_lead_meta_conversions WHERE event_name='ConvertedLead'", Integer.class));
    }

    @Test void failedOutboxInsertRollsBackTheStatusInTheSameStatement() {
        var service = service(request -> new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}"));
        jdbc.execute("ALTER TABLE trial_lead_meta_conversions ADD CONSTRAINT simulate_outbox_failure CHECK(event_name <> 'QualifiedLead')");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.updateLeadStatus(id, "QUALIFIED_LEAD"));
        assertEquals("BOOKED", jdbc.queryForObject("SELECT status FROM trial_leads WHERE id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM trial_lead_meta_conversions", Integer.class));
    }

    @Test void controllerKeepsFirstVisitorAttributionAndCrmStatusAndHonorsConsentRevocation() {
        AtomicInteger sends = new AtomicInteger();
        var service = service(request -> {
            sends.incrementAndGet(); return new MetaFunnelService.MetaResponse(200, "{\"events_received\":1}");
        });
        var controller = new TrialLeadController(jdbc, mock(UserRepository.class),
                mock(PushSubscriptionRepository.class), mock(WebPushService.class), service);
        HttpServletRequest firstVisitor = mock(HttpServletRequest.class);
        when(firstVisitor.getRemoteAddr()).thenReturn("8.8.8.8");
        when(firstVisitor.getHeader("User-Agent")).thenReturn("FirstVisitorBrowser");
        var first = controller.create(new TrialLeadController.CreateLeadRequest(
                "+4915123456789", "test", "first-visitor", null, null, null, null, null,
                "parent@example.com", "fb.1.1700000000000.111", "fb.1.1700000000000.first-click",
                "https://mindcrafti.de/ru/trial?email=private@example.com#form", true), firstVisitor);
        UUID leadId = jdbc.queryForObject("SELECT id FROM trial_leads WHERE tracking_token=?", UUID.class, first.trackingToken());
        controller.setStatus(leadId, Map.of("status", "BOOKED"));

        HttpServletRequest laterVisitor = mock(HttpServletRequest.class);
        when(laterVisitor.getRemoteAddr()).thenReturn("1.1.1.1");
        when(laterVisitor.getHeader("User-Agent")).thenReturn("LaterBrowser");
        controller.create(new TrialLeadController.CreateLeadRequest(
                "+4915123456789", "test", "first-visitor", "7", null, null, null, "new answer",
                "second@example.com", "fb.1.1700000000000.222", "fb.1.1700000000000.later-click",
                "https://mindcrafti.de/ru/other", true), laterVisitor);
        var stored = jdbc.queryForMap("SELECT status,email,meta_fbp,meta_fbc,meta_event_source_url,meta_client_ip,meta_user_agent FROM trial_leads WHERE id=?", leadId);
        assertEquals("BOOKED", stored.get("status"));
        assertEquals("parent@example.com", stored.get("email"));
        assertEquals("fb.1.1700000000000.111", stored.get("meta_fbp"));
        assertEquals("fb.1.1700000000000.first-click", stored.get("meta_fbc"));
        assertEquals("https://mindcrafti.de/ru/trial", stored.get("meta_event_source_url"));
        assertEquals("8.8.8.8", stored.get("meta_client_ip"));
        assertEquals("FirstVisitorBrowser", stored.get("meta_user_agent"));

        var response = controller.updatePublic(first.trackingToken(), new TrialLeadController.UpdateLeadRequest(
                null, null, null, null, null, null, null, "CONTRACT", null, null, null, null, false), laterVisitor);
        assertEquals("BOOKED", response.status(), "A public funnel request cannot enter a CRM outcome status");
        assertFalse(jdbc.queryForObject("SELECT meta_marketing_consent FROM trial_leads WHERE id=?", Boolean.class, leadId));
        service.deliverPending();
        assertEquals(0, sends.get());
        assertEquals("SUPPRESSED", jdbc.queryForObject("SELECT status FROM trial_lead_meta_conversions WHERE lead_id=? AND event_name='Schedule'", String.class, leadId));
    }

    private MetaFunnelService service(MetaFunnelService.Transport transport) {
        return new MetaFunnelService(jdbc, JsonMapper.builder().build(), "secret", null, true,
                "1391828005920802", "v26.0", transport);
    }

    private MetaFunnelService.PendingEvent pending() {
        return jdbc.query("SELECT lead_id,event_name,event_id,occurred_at,test_event_code FROM trial_lead_meta_conversions WHERE event_name='Schedule'",
                (rs, row) -> new MetaFunnelService.PendingEvent(rs.getObject("lead_id", UUID.class), rs.getString("event_name"),
                        rs.getString("event_id"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("test_event_code"))).get(0);
    }
    private String status() {
        return jdbc.queryForObject("SELECT status FROM trial_lead_meta_conversions WHERE event_name='Schedule'", String.class);
    }
}
