package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcschool.flashcard.AbstractIntegrationTest;
import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.students.StudentController;
import com.mcschool.flashcard.teachers.TeacherController;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** No outer test transaction: distinct transactions and receipt instances share real PostgreSQL. */
class McpOperationReceiptIntegrationTest extends AbstractIntegrationTest {
    @Autowired private McpOperationReceiptService receipts;
    @Autowired private McpAccessService access;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private StudentController students;
    @Autowired private TeacherController teachers;
    private User teacher;
    private AuthenticatedUser actor;

    @BeforeEach
    void seedActors() {
        User admin = users.save(User.bootstrapAdmin("Receipt Admin", "receipt@test.local", "hash"));
        teacher = users.save(User.activeTeacher("Receipt Teacher", "teacher@test.local", "receiptteacher", "hash"));
        actor = access.requireActor(admin);
    }

    @Test
    void concurrentRetriesAcrossInstancesPerformOneDomainWriteAndReturnTheSameResult() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var writes = new AtomicInteger();
        var restarted = new McpOperationReceiptService(new McpAccessService(jdbc), new McpOperationReceiptRepository(jdbc), json, transactions);
        try {
            var first = pool.submit(() -> receipts.execute(actor, teacher.getId(), "create_lesson", "same-key", Map.of("title", "Math"), () -> {}, () -> {
                writes.incrementAndGet();
                jdbc.update("UPDATE users SET full_name = full_name || '-saved' WHERE id = ?", teacher.getId());
                entered.countDown();
                await(release);
                return Map.of("saved", true, "lessonId", "stable-result");
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                secondStarted.countDown();
                return restarted.execute(actor, teacher.getId(), "create_lesson", "same-key", Map.of("title", "Math"), () -> {}, () -> {
                    writes.incrementAndGet();
                    return Map.of("duplicated", true);
                });
            }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(first.get(10, TimeUnit.SECONDS));
            assertThat(writes.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, teacher.getId())).isEqualTo("Receipt Teacher-saved");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts", Integer.class)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void domainFailureRollsBackReceiptAndBusinessWriteSoTheSameKeyCanBeRetried() {
        assertThatThrownBy(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "rollback-key", Map.of(), () -> {}, () -> {
            jdbc.update("UPDATE users SET full_name = 'must roll back' WHERE id = ?", teacher.getId());
            throw new IllegalStateException("failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, teacher.getId())).isEqualTo("Receipt Teacher");
        assertThat(receipts.execute(actor, teacher.getId(), "update_lesson", "rollback-key", Map.of(), () -> {}, () -> Map.of("saved", true)))
                .isEqualTo(Map.of("saved", true));
    }

    @Test
    void canonicalArgumentOrderAndNumbersReplayButChangedPayloadConflicts() {
        var writes = new AtomicInteger();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("title", "Math"); first.put("count", 1);
        Object result = receipts.execute(actor, teacher.getId(), "create_lesson", "canonical", first, () -> {}, () -> {
            writes.incrementAndGet(); return Map.of("saved", true);
        });
        assertThat(receipts.execute(actor, teacher.getId(), "create_lesson", "canonical", Map.of("count", 1.0, "title", "Math"), () -> {}, () -> {
            writes.incrementAndGet(); return Map.of("wrong", true);
        })).isEqualTo(result);
        assertThat(writes.get()).isEqualTo(1);
        assertStatus(() -> receipts.execute(actor, teacher.getId(), "create_lesson", "canonical", Map.of("title", "Changed"), () -> {}, () -> Map.of()), HttpStatus.CONFLICT);
    }

    @Test
    void cachedResultNeverBypassesFreshTargetOwnershipOrActorAndTeacherEligibility() {
        receipts.execute(actor, teacher.getId(), "update_lesson", "authority", Map.of(), () -> {}, () -> Map.of("private", "result"));
        assertStatus(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "authority", Map.of(),
                () -> { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "target moved"); }, () -> Map.of()), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE users SET archived = true WHERE id = ?", teacher.getId());
        assertStatus(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "authority", Map.of(), () -> {}, () -> Map.of()), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE users SET archived = false WHERE id = ?", teacher.getId());
        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", actor.id());
        assertStatus(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "authority", Map.of(), () -> {}, () -> Map.of()), HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor", "teacher"})
    void waitingRetryRechecksAuthorityAfterAnotherTransactionCommits(String revoked) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var firstEntered = new CountDownLatch(1);
        var secondPrechecked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "revoke-during-wait", Map.of(), () -> {}, () -> {
                firstEntered.countDown();
                await(release);
                jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", revoked.equals("actor") ? actor.id() : teacher.getId());
                return Map.of("private", "must not replay after authority changes");
            }));
            assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> receipts.execute(actor, teacher.getId(), "update_lesson", "revoke-during-wait", Map.of(),
                    secondPrechecked::countDown, () -> Map.of("wrong", true)));
            assertThat(secondPrechecked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ResponseStatusException.class)
                    .satisfies(failure -> assertThat(((ResponseStatusException) failure.getCause()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void receiptScopeSeparatesOriginalActorsTeachersAndOperations() {
        User otherTeacher = users.save(User.activeTeacher("Second Teacher", "second@test.local", "secondteacher", "hash"));
        User otherAdmin = users.save(User.bootstrapAdmin("Second Admin", "secondadmin@test.local", "hash"));
        var otherActor = access.requireActor(otherAdmin);
        receipts.execute(actor, teacher.getId(), "create_lesson", "same", Map.of(), () -> {}, () -> Map.of("result", 1));
        receipts.execute(actor, otherTeacher.getId(), "create_lesson", "same", Map.of(), () -> {}, () -> Map.of("result", 2));
        receipts.execute(otherActor, teacher.getId(), "create_lesson", "same", Map.of(), () -> {}, () -> Map.of("result", 3));
        receipts.execute(actor, teacher.getId(), "update_lesson", "same", Map.of(), () -> {}, () -> Map.of("result", 4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts", Integer.class)).isEqualTo(4);
    }

    @Test
    void oversizedResultRollsBackTheReceiptAndRejectsInvalidKeys() {
        assertStatus(() -> receipts.execute(actor, teacher.getId(), "create_lesson", "large", Map.of(), () -> {},
                () -> Map.of("binary", "x".repeat(262144))), HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts", Integer.class)).isZero();
        for (String key : new String[]{"", " ", "x".repeat(129), "line\nfeed"}) {
            assertStatus(() -> receipts.execute(actor, teacher.getId(), "create_lesson", key, Map.of(), () -> {}, () -> Map.of()), HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void delegationPreservesTheRealProxiedControllersMethodSecurity() {
        var selected = access.requireTeacher(actor, teacher.getId());
        assertThat(access.asActor(actor, teachers::listTeachers)).hasSize(1);
        assertThat(access.asTeacher(actor, selected, () -> students.listStudents(selected))).isEmpty();
        assertThatThrownBy(() -> access.asTeacher(actor, selected, teachers::listTeachers))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.asActor(actor, () -> students.listStudents(actor)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void failedExternalActionLeavesItsCommittedClaimAndIsNeverAutomaticallyRepeated() {
        var actions = new AtomicInteger();
        assertThatThrownBy(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-failure", Map.of("file", "hash"), () -> {}, () -> {
            actions.incrementAndGet(); // Stand-in for a provider which may already have accepted the action.
            jdbc.update("UPDATE users SET full_name='must roll back' WHERE id=?", teacher.getId());
            throw new IllegalStateException("provider outcome unknown");
        })).isInstanceOf(McpOperationReceiptService.ExternalOperationPendingException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts WHERE result_json IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id=?", String.class, teacher.getId())).isEqualTo("Receipt Teacher");
        var restarted = new McpOperationReceiptService(new McpAccessService(jdbc), new McpOperationReceiptRepository(jdbc), json, transactions);
        assertThatThrownBy(() -> restarted.executeExternal(actor, teacher.getId(), "drive_upload", "external-failure", Map.of("file", "hash"), () -> {}, () -> {
            actions.incrementAndGet(); return Map.of("duplicated", true);
        })).isInstanceOf(McpOperationReceiptService.ExternalOperationPendingException.class);
        assertThat(actions.get()).isEqualTo(1);
        assertStatus(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-failure", Map.of("file", "changed"), () -> {}, () -> Map.of()), HttpStatus.CONFLICT);
    }

    @Test
    void successfulExternalActionAndResultCommitIndependentlyOfAnOuterRollbackAndReplayAfterRestart() {
        var actions = new AtomicInteger();
        new TransactionTemplate(transactions).execute(status -> {
            Object saved = receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-success", Map.of(), () -> {}, () -> {
                actions.incrementAndGet();
                jdbc.update("UPDATE users SET full_name='External committed' WHERE id=?", teacher.getId());
                return Map.of("fileId", "provider-stable-id");
            });
            assertThat(saved).isEqualTo(Map.of("fileId", "provider-stable-id"));
            status.setRollbackOnly();
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id=?", String.class, teacher.getId())).isEqualTo("External committed");
        var restarted = new McpOperationReceiptService(new McpAccessService(jdbc), new McpOperationReceiptRepository(jdbc), json, transactions);
        assertThat(restarted.executeExternal(actor, teacher.getId(), "drive_upload", "external-success", Map.of(), () -> {}, () -> {
            actions.incrementAndGet(); return Map.of("duplicated", true);
        })).isEqualTo(Map.of("fileId", "provider-stable-id"));
        assertThat(actions.get()).isEqualTo(1);
    }

    @Test
    void concurrentExternalRetryObservesPendingClaimWithoutStartingASecondProviderAction() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var actions = new AtomicInteger();
        try {
            var first = pool.submit(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-concurrent", Map.of(), () -> {}, () -> {
                actions.incrementAndGet(); entered.countDown(); await(release); return Map.of("saved", true);
            }));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-concurrent", Map.of(), () -> {}, () -> {
                actions.incrementAndGet(); return Map.of("duplicated", true);
            }));
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(McpOperationReceiptService.ExternalOperationPendingException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(Map.of("saved", true));
            assertThat(actions.get()).isEqualTo(1);
            assertThat(receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-concurrent", Map.of(), () -> {}, () -> Map.of("wrong", true)))
                    .isEqualTo(Map.of("saved", true));
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void externalReplayRechecksTargetsAndTeacherAndWorkCannotBypassSecondStageChecks() {
        receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-auth", Map.of(), () -> {}, () -> Map.of("saved", true));
        assertStatus(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-auth", Map.of(),
                () -> { throw new ResponseStatusException(HttpStatus.FORBIDDEN, "target moved"); }, () -> Map.of()), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE users SET archived=true WHERE id=?", teacher.getId());
        assertStatus(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-auth", Map.of(), () -> {}, () -> Map.of()), HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE users SET archived=false WHERE id=?", teacher.getId());
        var checks = new AtomicInteger();
        var actions = new AtomicInteger();
        assertStatus(() -> receipts.executeExternal(actor, teacher.getId(), "drive_upload", "external-second-stage", Map.of(), () -> {
            if (checks.incrementAndGet() >= 3) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ownership changed after claim commit");
        }, () -> { actions.incrementAndGet(); return Map.of(); }), HttpStatus.FORBIDDEN);
        assertThat(actions.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mcp_operation_receipts WHERE idempotency_key='external-second-stage' AND result_json IS NULL", Integer.class)).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for concurrent test"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(status));
    }
}
