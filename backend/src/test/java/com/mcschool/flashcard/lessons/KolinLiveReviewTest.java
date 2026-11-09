package com.mcschool.flashcard.lessons;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.mcschool.flashcard.homeworks.HomeworkRepository;
import com.mcschool.flashcard.monthlyplans.MonthlyPlanService;
import com.mcschool.flashcard.settings.SchoolPromptSettingsService;
import com.mcschool.flashcard.users.UserRepository;
import com.mcschool.flashcard.liveclasses.KolinTaskService;
import com.mcschool.flashcard.liveclasses.InteractiveTemplateSpec;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in paid checks with synthetic lesson data only. Never run implicitly in CI. */
@EnabledIfEnvironmentVariable(named="KOLYN_LIVE_CHECK",matches="true")
class KolinLiveReviewTest {
    final JsonMapper mapper=JsonMapper.builder().build();
    final List<Map<String,Object>> report=new ArrayList<>();
    AiLessonPilotService ai;
    @BeforeEach void setup() throws Exception {
        String key=System.getenv("OPENAI_API_KEY");assertThat(key).isNotBlank();
        ai=new AiLessonPilotService(key,"unused",mapper,mock(GoogleCalendarLessonService.class),mock(LessonPreparationService.class),
                mock(LessonPreparationRepository.class),mock(MonthlyPlanService.class),mock(SchoolPromptSettingsService.class),mock(UserRepository.class),mock(HomeworkRepository.class));
        var proxy=mock(HttpClient.class);var real=HttpClient.newHttpClient();
        when(proxy.send(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenAnswer(call->{
            long start=System.nanoTime();var response=real.send(call.getArgument(0),HttpResponse.BodyHandlers.ofString());
            var body=mapper.readTree(response.body());
            report.add(Map.of("httpStatus",response.statusCode(),"milliseconds",(System.nanoTime()-start)/1_000_000,
                    "usage",body.path("usage"),"responseStatus",body.path("status").asString("unknown")));
            return response;
        });
        ReflectionTestUtils.setField(ai,"httpClient",proxy);
    }
    @AfterEach void report(TestInfo info) throws Exception {
        Files.createDirectories(Path.of("target/kolyn-live-review"));
        Files.writeString(Path.of("target/kolyn-live-review",info.getTestMethod().orElseThrow().getName()+".json"),mapper.writeValueAsString(report));
    }
    @Test void conversationDoesNotCreateWorksheetsAndExplicitRequestsStillDo() {
        var history=List.of(Map.of("role","user","text","Сделай задания к Abitur"),Map.of("role","assistant","text","Подготовлено 5 заданий в PDF."));
        for(String question:List.of("Что ты вообще умеешь?","Это такие лёгкие задания в Abitur?","Как лучше объяснить дробь?")) {
            var answer=ai.kolinDialogue("{}",history,question);
            report.add(Map.of("question",question,"mode",answer.mode(),"answer",answer.text()));
            assertThat(answer.mode()).isEqualTo("answer");assertThat(answer.text().length()).isGreaterThan(80);
        }
        for(String question:List.of("Дай Мелиссе пять заданий по дробям","Сделай интерактивную пирамиду")) {
            var answer=ai.kolinDialogue("{}",List.of(),question);
            assertThat(answer.mode()).isEqualTo("generate");
        }
    }
    @Test void usesActualProvidedTranscriptInsteadOfInventingLessonContent() {
        String context="""
                {"transcript":{"metadata":{"state":"included","segmentCount":2,"truncated":false,"transcriptionActive":true},
                "segments":[{"speakerName":"QA Teacher","speakerRole":"teacher","text":"Сейчас решаем две седьмых плюс три седьмых."},
                {"speakerName":"QA Pupil","speakerRole":"student","text":"Получается пять четырнадцатых?"}]}}
                """;
        var answer=ai.kolinDialogue(context,List.of(),"Какую ошибку только что сделала ученица и как ей объяснить?");
        report.add(Map.of("answer",answer.text()));
        assertThat(answer.mode()).isEqualTo("answer");
        assertThat(answer.text()).contains("7");assertThat(answer.text()).containsAnyOf("5/7","5}{7","пять седьмых");
    }
    @Test void requestedPdfAndInteractiveStillUseTheExistingCheckedGeneratorContracts() throws Exception {
        String pupil="11111111-1111-4111-8111-111111111111";
        String context=mapper.writeValueAsString(Map.of("roster",List.of(Map.of("id",pupil,"name","QA Melissa")),
                "selectedStudentId",pupil,"targetType","student","requestedPdfTaskCount",5,"requestedInteractiveTaskCount",1));
        var worksheet=mapper.readValue(ai.kolinStructuredReply(context,List.of(),"Создай QA Melissa 5 заданий в PDF на сложение и деление обыкновенных дробей."),KolinTaskService.Content.class);
        assertThat(worksheet.kind()).isEqualTo("worksheet");assertThat(worksheet.tasks()).hasSize(5);
        Object document=ReflectionTestUtils.invokeMethod(KolinTaskService.class,"document",worksheet.tasks());
        Files.createDirectories(Path.of("target/kolyn-live-review"));
        Files.writeString(Path.of("target/kolyn-live-review/fractions-document.json"),mapper.writeValueAsString(document));
        var interactive=mapper.readValue(ai.kolinStructuredReply(context,List.of(),"Создай QA Melissa одну интерактивную правильную четырёхугольную пирамиду: можно менять длину стороны основания и высоту, видеть объём. Нужна демонстрация, не проверка ответа."),KolinTaskService.Content.class);
        assertThat(interactive.kind()).isEqualTo("interactive");assertThat(interactive.tasks()).hasSize(1);
        var checked=InteractiveTemplateSpec.validate(interactive.tasks().get(0).visual());
        assertThat(checked.get("template")).isEqualTo("solid");
        report.add(Map.of("pdfTasks",worksheet.tasks().size(),"interactiveTemplate",checked.get("template"),"interactiveValidated",true));
    }
    @Test void reviewsTwoPagesWithWrittenMistakesAndReturnsPageLocations() throws Exception {
        String context="""
                {"boardReview":{"studentName":"QA Melissa","scope":"workbook","totalPageCount":2,"capturedAt":"2026-11-09T16:00:00Z"}}
                """;
        var images=List.<Map<String,Object>>of(Map.of("pageNumber",1,"imageDataUrl",page("Task 1: 1/2 + 1/4", "1/2 + 1/4 = 2/6")),
                Map.of("pageNumber",2,"imageDataUrl",page("Task 2: Solve 7x = 21", "7x = 21", "x = 4")));
        String answer=ai.kolinReviewReply(context,List.of(),"Что неправильно решила QA Melissa? Укажи страницы и правильные ответы обычной записью, без LaTeX.",images);
        report.add(Map.of("answer",answer));
        assertThat(answer).containsAnyOf("3/4","0,75","0.75");
        assertThat(answer.replaceAll("\\s+","").replace('х','x')).contains("x=3");
        assertThat(answer.toLowerCase(Locale.ROOT)).contains("страниц");
        assertThat(answer).contains("1","2");
    }
    static String page(String title,String... lines) throws Exception {
        var image=new BufferedImage(1200,1600,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,1200,1600);g.setColor(Color.BLACK);g.setFont(new Font("SansSerif",Font.PLAIN,36));g.drawString(title,70,100);
        g.setColor(new Color(30,70,170));g.setFont(new Font("SansSerif",Font.ITALIC,46));int y=220;
        for(String line:lines){g.drawString(line,100,y);y+=90;}g.dispose();
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"jpeg",bytes);
        return "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
