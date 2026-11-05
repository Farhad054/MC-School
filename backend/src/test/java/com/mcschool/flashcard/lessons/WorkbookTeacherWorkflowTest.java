package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.auth.McpOAuthService;
import com.mcschool.flashcard.drive.TeacherGoogleDriveDownloadService;
import com.mcschool.flashcard.lessons.dto.GroupLessonResponse;
import com.mcschool.flashcard.monthlyplans.MonthlyPlanService;
import com.mcschool.flashcard.monthlyplans.MonthlyPlanTargetType;
import com.mcschool.flashcard.monthlyplans.dto.MonthlyPlanResponse;
import com.mcschool.flashcard.settings.*;
import com.mcschool.flashcard.trialleads.FunnelAnalyticsService;
import com.mcschool.flashcard.users.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkbookTeacherWorkflowTest {
    private final ObjectMapper json=new ObjectMapper();
    private final User a=User.activeTeacher("Teacher A","a@test.local","a","unused");
    private final User b=User.activeTeacher("Teacher B","b@test.local","b","unused");
    private final User admin=User.bootstrapAdmin("Admin","admin@test.local","unused");
    private final User student=User.managedStudent("Sofia",a);
    private final UserRepository users=mock(UserRepository.class);
    private final McpOAuthService oauth=mock(McpOAuthService.class);
    private final GoogleCalendarLessonService calendar=mock(GoogleCalendarLessonService.class);
    private final LessonPreparationService preparation=mock(LessonPreparationService.class);
    private final LessonPreparationRepository preparations=mock(LessonPreparationRepository.class);
    private final McpAnalyticsService analytics=mock(McpAnalyticsService.class);
    private final McpHomeworkReadService homework=mock(McpHomeworkReadService.class);
    private final MonthlyPlanService plans=mock(MonthlyPlanService.class);
    private final SchoolPromptSettingsService prompts=mock(SchoolPromptSettingsService.class);
    private final SchoolBrandGuideService brand=mock(SchoolBrandGuideService.class);
    private final McpAccessService access=mock(McpAccessService.class);
    private final McpOperationReceiptService receipts=mock(McpOperationReceiptService.class);
    private final WorkbookContentService content=mock(WorkbookContentService.class);
    private final OwnerWorkbookPdfRenderService renderer=mock(OwnerWorkbookPdfRenderService.class);
    private MindcraftiMcpController controller;

    @BeforeEach
    void setup() throws Exception {
        for(User user:List.of(a,b,admin,student)) {
            when(oauth.resolveAccessToken(user.getEmail())).thenReturn(Optional.of(user));
            when(users.findById(user.getId())).thenReturn(Optional.of(user));
            when(access.requireActor(user)).thenReturn(principal(user));
        }
        when(access.requireTeacher(any(),nullable(UUID.class))).thenAnswer(invocation->{
            AuthenticatedUser actor=invocation.getArgument(0);
            UUID target=invocation.getArgument(1);
            if(target==null && actor.role()==Role.TEACHER) target=actor.id();
            if(target==null || actor.role()==Role.TEACHER && !target.equals(actor.id()))
                throw new org.springframework.security.access.AccessDeniedException("Own lessons only");
            return principal(target.equals(a.getId())?a:b);
        });
        when(access.asTeacher(any(),any(),any())).thenAnswer(i->((Supplier<?>)i.getArgument(2)).get());
        Map<String,Object> cache=new HashMap<>();
        when(receipts.execute(any(),any(),anyString(),anyString(),any(),any(),any())).thenAnswer(i->{
            ((Runnable)i.getArgument(5)).run();
            String key=i.getArgument(2)+":"+i.getArgument(3);
            return cache.computeIfAbsent(key,k->((Supplier<?>)i.getArgument(6)).get());
        });
        Instant start=Instant.parse("2026-11-06T12:00:00Z");
        var lesson=new GroupLessonResponse("lesson-a","lesson-a",null,null,student.getId(),"Sofia",List.of(),
                "Sofia Mathematics",start,start.plusSeconds(3600),null,null);
        when(calendar.listGroupLessons(principal(a))).thenReturn(List.of(lesson));
        when(calendar.listGroupLessons(principal(b))).thenReturn(List.of());
        when(plans.get(principal(a),"student",student.getId(),"2026-11")).thenReturn(
                MonthlyPlanResponse.empty(MonthlyPlanTargetType.STUDENT,student.getId(),"2026-11"));
        when(preparations.findByTeacherIdAndEventId(any(),anyString())).thenReturn(Optional.empty());
        when(analytics.studentActivity(any(),anyBoolean(),any(),any(),any())).thenReturn(Map.of("totals",Map.of(),"errors",List.of()));
        when(homework.findStudentHomeworks(any(),anyBoolean(),any(),any(),any())).thenReturn(Map.of("homeworkCount",0,"submittedCount",0));
        when(homework.recentSubmissions(any(),anyBoolean(),any(),anyInt())).thenReturn(Map.of("recentSubmissions",List.of()));
        when(prompts.readForMcp()).thenReturn(new SchoolPromptSettingsResponse("group-current","individual-current","","","workbook-current","",Instant.now()));
        when(brand.readForMcp()).thenReturn(Map.of("guideText","brand-current","base64",Base64.getEncoder().encodeToString("official-guide".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        String document=json.writeValueAsString(Map.of("meta",Map.of("durationMinutes",60)));
        when(content.generate(anyMap(),anyString())).thenReturn(new WorkbookContentService.Content(document,"", ""));
        when(renderer.create(any(),anyString(),anyString(),anyString(),anyString())).thenAnswer(i->new LinkedHashMap<>(Map.of(
                "draftId","draft-one","eventId","lesson-a","published",false)));
        controller=new MindcraftiMcpController("import-key",json,users,oauth,calendar,preparation,preparations,
                mock(TranscriptLessonGuard.class),mock(TeacherGoogleDriveDownloadService.class),mock(McpHomeworkSeriesService.class),
                analytics,homework,mock(McpHomeworkWriteService.class),plans,prompts,brand,mock(LessonPdfDraftService.class),
                mock(StoryTemplateMasterService.class),mock(StoryTemplateDownloadLinkService.class),mock(FunnelAnalyticsService.class));
        controller.setPersonalWorkbookAccess(access);
        controller.setPersonalWorkbookReceipts(receipts);
        controller.setOwnerWorkbookPdfRenderService(renderer);
        controller.setOwnerWorkbookGeneratorService(new OwnerWorkbookGeneratorService());
        controller.setWorkbookContentService(content);
    }

    @Test
    void ordinaryTeacherCanGenerateOnlyOwnLessonWithoutPublishingAndRetryReusesDraft() throws Exception {
        Map<String,Object> args=Map.of("eventId","lesson-a","instruction","Mehr Graphen","idempotencyKey","generation-one");
        assertThat(call(a,"generate_lesson_workbook_draft",args)).doesNotContainKey("error");
        assertThat(call(a,"generate_lesson_workbook_draft",args)).doesNotContainKey("error");
        verify(content,times(1)).generate(argThat(ctx->ctx.get("teachingPrompt").equals("individual-current")
                && ctx.get("workbookPrompt").equals("workbook-current") && Boolean.FALSE.equals(ctx.get("monthPlanAvailable"))
                && !((Map<?,?>)ctx.get("brandGuide")).containsKey("base64")),eq("Mehr Graphen"));
        verify(renderer,times(1)).create(eq(principal(a)),eq("lesson-a"),anyString(),eq(""),eq(""));
        verifyNoInteractions(preparation);
    }

    @Test
    void teacherBCannotGenerateTeacherALessonAndStudentsHaveNoGeneratorAccess() throws Exception {
        Map<String,Object> args=Map.of("teacherId",a.getId().toString(),"eventId","lesson-a","idempotencyKey","forged");
        assertThat(call(b,"generate_lesson_workbook_draft",args)).containsKey("error");
        assertThat(call(student,"generate_lesson_workbook_draft",args)).containsKey("error");
        verifyNoInteractions(content,renderer,receipts);
    }

    @Test
    void adminCanGenerateForSelectedTeacherAndTeacherCannotFetchSources() throws Exception {
        assertThat(call(admin,"generate_lesson_workbook_draft",Map.of("teacherId",a.getId().toString(),"eventId","lesson-a","idempotencyKey","admin-gen"))).doesNotContainKey("error");
        verify(renderer).create(eq(principal(a)),eq("lesson-a"),anyString(),anyString(),anyString());
        assertThat(call(a,"get_owner_workbook_generator",Map.of())).containsKey("error");
        assertThat(call(admin,"get_owner_workbook_generator",Map.of())).doesNotContainKey("error");
    }

    @Test
    void teacherPreparedJsonUsesServerRendererWithoutAnyApiCall() throws Exception {
        String token=contextToken();
        Map<String,Object> args=Map.of("eventId","lesson-a","idempotencyKey","prepared-one","lessonJson",preparedJson(),"contextToken",token);
        assertThat(call(a,"generate_lesson_workbook_draft",args)).doesNotContainKey("error");
        assertThat(call(a,"generate_lesson_workbook_draft",args)).doesNotContainKey("error");
        verify(renderer).create(eq(principal(a)),eq("lesson-a"),anyString(),eq(""),eq(""));
        verifyNoInteractions(content,preparation);
        when(prompts.readForMcp()).thenReturn(new SchoolPromptSettingsResponse("changed","changed","","","changed","",Instant.now()));
        assertThat(call(a,"generate_lesson_workbook_draft",args)).containsKey("error");
        verify(renderer,times(1)).create(any(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void changedSchoolPromptInvalidatesPreparedJsonTokenBeforeRendering() throws Exception {
        String token=contextToken();
        when(prompts.readForMcp()).thenReturn(new SchoolPromptSettingsResponse("group-current","individual-UPDATED","","","workbook-current","",Instant.now()));
        assertThat(call(a,"generate_lesson_workbook_draft",Map.of("eventId","lesson-a","idempotencyKey","stale-one",
                "lessonJson",preparedJson(),"contextToken",token))).containsKey("error");
        verifyNoInteractions(content,renderer,preparation);
    }

    @Test
    void teacherCompatibilityAliasesRequireFreshContextAndIdempotencyAndReuseDraft() throws Exception {
        for(String tool:List.of("generate_workbook","create_lesson_draft")) {
            assertThat(call(a,tool,Map.of("eventId","lesson-a","lessonJson",preparedJson()))).containsKey("error");
        }
        verifyNoInteractions(renderer,content,receipts);
        String token=contextToken();
        Map<String,Object> args=Map.of("eventId","lesson-a","lessonJson",preparedJson(),"contextToken",token,"idempotencyKey","compat-generate-one");
        assertThat(call(a,"generate_workbook",args)).doesNotContainKey("error");
        assertThat(call(a,"generate_workbook",args)).doesNotContainKey("error");
        verify(renderer,times(1)).create(eq(principal(a)),eq("lesson-a"),anyString(),eq(""),eq(""));
        when(prompts.readForMcp()).thenReturn(new SchoolPromptSettingsResponse("changed","changed","","","changed","",Instant.now()));
        assertThat(call(a,"generate_workbook",args)).containsKey("error");
        verify(renderer,times(1)).create(any(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(content,preparation);
    }

    @Test
    void structuredCreateDraftAliasUsesCanonicalRendererAndAdminLegacyMayOmitKeyAndToken() throws Exception {
        String token=contextToken();
        Map<String,Object> args=Map.of("eventId","lesson-a","lessonJson",preparedJson(),"contextToken",token,"idempotencyKey","compat-create-one");
        assertThat(call(a,"create_lesson_draft",args)).doesNotContainKey("error");
        assertThat(call(a,"create_lesson_draft",args)).doesNotContainKey("error");
        verify(renderer,times(1)).create(eq(principal(a)),eq("lesson-a"),anyString(),eq(""),eq(""));
        assertThat(call(admin,"create_lesson_draft",Map.of("teacherId",a.getId().toString(),"eventId","lesson-a","lessonJson",preparedJson()))).doesNotContainKey("error");
        verify(renderer,times(2)).create(eq(principal(a)),eq("lesson-a"),anyString(),eq(""),eq(""));
        verifyNoInteractions(content,preparation);
    }

    @SuppressWarnings("unchecked")
    private String contextToken() {
        Map<String,Object> envelope=call(a,"prepare_workbook_context",Map.of("eventId","lesson-a"));
        assertThat(envelope).doesNotContainKey("error");
        Map<String,Object> result=(Map<String,Object>)envelope.get("result");
        Map<String,Object> context=(Map<String,Object>)result.get("structuredContent");
        assertThat(context).containsKey("rendererContract");
        return String.valueOf(context.get("contextToken"));
    }

    private String preparedJson() {
        return json.writeValueAsString(Map.of("meta",Map.of("school","MINDCRAFTI SCHOOL","durationMinutes",60),
                "workbookPages",List.of(Map.of("title","Solve","blocks",List.of(Map.of("type","exercise","title","Expand")))),
                "teacherPages",List.of(Map.of("title","Answers","blocks",List.of(Map.of("type","solutions","items",List.of())))),
                "qa",Map.of("algebra",List.of(Map.of("kind","equivalence","expression","(x+3)^2","answer","x^2+6*x+9")))));
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> call(User user,String tool,Map<String,Object> args) {
        return (Map<String,Object>)controller.post("Bearer "+user.getEmail(),null,Map.of("jsonrpc","2.0","id",1,
                "method","tools/call","params",Map.of("name",tool,"arguments",args))).getBody();
    }
    private AuthenticatedUser principal(User user) { return new AuthenticatedUser(user.getId(),user.getEmail(),user.getRole()); }
}
