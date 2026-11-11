package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.*;
import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL queue, transactions and current role isolation. No model API calls or real pupil data. */
@SpringBootTest(properties={"app.workbook-jobs.initial-ms=86400000","app.online-classes.idle-sweep-initial-ms=86400000"})
@EnabledIf("databaseAvailable")
class McpWorkbookJobIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired McpAccessService access;
    @Autowired WorkbookGenerationJobService jobs;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    User a,b,admin;
    AuthenticatedUser teacher;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) { McpTestDatabase.properties(properties); }
    static boolean databaseAvailable(){return McpTestDatabase.available();}
    @BeforeEach void seed(){
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
        a=users.save(User.activeTeacher("A","a@test.local","a","unused"));
        b=users.save(User.activeTeacher("B","b@test.local","b","unused"));
        admin=users.save(User.bootstrapAdmin("Admin","admin@test.local","unused"));
        teacher=access.requireActor(a);
    }
    Map<String,Object> request(){return Map.of("eventId","lesson-a","instruction","Fractions","idempotencyKey","key-one");}
    UUID enqueue(){return UUID.fromString((String)jobs.enqueue(teacher,a.getId(),"lesson-a","key-one",request()).get("jobId"));}
    UUID saveDraft(){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO lesson_pdf_drafts(id,teacher_id,event_id,workbook_filename,answers_filename,workbook_pdf,answers_pdf,task_count) VALUES (?,?,?,'work.pdf','answers.pdf',?,?,5)",
                id,a.getId(),"lesson-a",new byte[]{1},new byte[]{2});return id;
    }
    Map<String,Object> finish(UUID id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            var status=jobs.status(teacher,id);
            if(Set.of("SUCCEEDED","FAILED").contains(status.get("status")))return status;
            Thread.sleep(20);
        }
        throw new AssertionError("Job did not finish");
    }
    @Test void aSlowGenerationDoesNotBlockPollingOrIdenticalRetries()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        jobs.setGenerator((actor,args)->{calls.incrementAndGet();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return Map.of("draftId",saveDraft().toString());});
        UUID id=enqueue();jobs.tick();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        var pool=Executors.newSingleThreadExecutor();
        try {
            assertThat(pool.submit(()->jobs.status(teacher,id)).get(1,TimeUnit.SECONDS)).containsEntry("status","RUNNING");
            assertThat(pool.submit(this::enqueue).get(1,TimeUnit.SECONDS)).isEqualTo(id);
        } finally {release.countDown();pool.shutdownNow();}
        assertThat(finish(id)).containsEntry("status","SUCCEEDED").containsKey("draftId");
        assertThat(enqueue()).isEqualTo(id);jobs.tick();assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT request_json FROM workbook_generation_jobs WHERE id=?",String.class,id)).isNull();
    }
    @Test void concurrentClaimsAcrossInstancesReuseOneDurableJob()throws Exception {
        var second=new WorkbookGenerationJobService(jdbc,access,json,transactions);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(this::enqueue);
            var two=pool.submit(()->second.enqueue(teacher,a.getId(),"lesson-a","key-one",request()));
            assertThat(two.get(5,TimeUnit.SECONDS).get("jobId")).isEqualTo(one.get(5,TimeUnit.SECONDS).toString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workbook_generation_jobs",Integer.class)).isEqualTo(1);
        }finally {pool.shutdownNow();second.close();}
    }
    @Test void changedRequestCannotReuseAKeyAndTeachersCannotReadOrGenerateEachOthersJobs(){
        UUID id=enqueue();
        assertThatThrownBy(()->jobs.enqueue(teacher,a.getId(),"lesson-a","key-one",Map.of("instruction","Changed"))).hasMessageContaining("409");
        assertThatThrownBy(()->jobs.status(access.requireActor(b),id)).hasMessageContaining("404");
        assertThatThrownBy(()->jobs.enqueue(access.requireActor(b),a.getId(),"lesson-a","key-two",request())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->jobs.status(access.requireActor(admin),id)).hasMessageContaining("404");
    }
    @Test void failedGenerationRollsBackDraftAndDoesNotRetryAutomatically()throws Exception {
        var calls=new AtomicInteger();
        jobs.setGenerator((actor,args)->{calls.incrementAndGet();saveDraft();throw new IllegalArgumentException("Invalid math");});
        UUID id=enqueue();jobs.tick();
        assertThat(finish(id)).containsEntry("status","FAILED").containsEntry("errorCode","VALIDATION_FAILED").doesNotContainKey("draftId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_pdf_drafts",Integer.class)).isZero();
        assertThat(enqueue()).isEqualTo(id);jobs.tick();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void aRestartKeepsQueuedWorkAndMarksExpiredWorkInterruptedWithoutRegeneration()throws Exception {
        UUID id=enqueue();
        var restarted=new WorkbookGenerationJobService(jdbc,access,json,transactions);
        var calls=new AtomicInteger();
        try {
            assertThat(restarted.status(teacher,id)).containsEntry("status","QUEUED");
            jdbc.update("UPDATE workbook_generation_jobs SET state='RUNNING',lease_until=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE id=?",id);
            restarted.setGenerator((actor,args)->{calls.incrementAndGet();return Map.of("draftId",saveDraft().toString());});
            restarted.tick();
            assertThat(restarted.status(teacher,id)).containsEntry("errorCode","INTERRUPTED");assertThat(calls.get()).isZero();
        }finally {restarted.close();}
    }
    @Test void revokedAccountCannotPollAndQueuedJobCannotReachGenerator()throws Exception {
        var calls=new AtomicInteger();jobs.setGenerator((actor,args)->{calls.incrementAndGet();return Map.of();});
        UUID id=enqueue();jdbc.update("UPDATE users SET archived=true WHERE id=?",a.getId());
        assertThatThrownBy(()->jobs.status(teacher,id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        jobs.tick();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline&&!"FAILED".equals(jdbc.queryForObject("SELECT state FROM workbook_generation_jobs WHERE id=?",String.class,id)))Thread.sleep(20);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM workbook_generation_jobs WHERE id=?",String.class,id)).isEqualTo("ACCESS_CHANGED");assertThat(calls.get()).isZero();
    }
}
