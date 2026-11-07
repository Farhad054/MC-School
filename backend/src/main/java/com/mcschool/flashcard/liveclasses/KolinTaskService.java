package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.lessons.AiLessonPilotService;
import com.mcschool.flashcard.lessons.OwnerWorkbookPdfRenderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Single provider pass, deterministic math rendering, then a private durable draft. */
@Service
public class KolinTaskService {
    public record Task(String kind,String instruction,String expression,String answer,String variable,List<String> restrictions) {}
    public record Content(String kind,String text,String studentId,List<Task> tasks) {}
    private record Fingerprint(String text,List<KolinChatService.HistoryMessage> history,KolinChatService.Context context) {}
    private final AiLessonPilotService ai;
    private final KolinTaskRoster roster;
    private final KolinWorksheetStore store;
    private final OwnerWorkbookPdfRenderService renderer;
    private final ObjectMapper mapper;
    public KolinTaskService(AiLessonPilotService ai,KolinTaskRoster roster,KolinWorksheetStore store,
                            OwnerWorkbookPdfRenderService renderer,ObjectMapper mapper) {
        this.ai=ai; this.roster=roster; this.store=store; this.renderer=renderer; this.mapper=mapper;
    }

    public KolinChatService.Reply reply(AuthenticatedUser teacher,OnlineClass room,KolinChatService.Request request,
            Map<String,Object> context,List<Map<String,String>> history,String question,
            KolinTranscriptContextService.Metadata transcript) throws Exception {
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(
                new Fingerprint(question,history.stream().map(item -> new KolinChatService.HistoryMessage(item.get("role"),item.get("text"))).toList(),request.context()))
                .getBytes(StandardCharsets.UTF_8)));
        var reserved=store.begin(teacher,room.getId(),request.clientMessageId(),hash);
        if(reserved.cached()!=null) return reserved.cached();
        try {
            var pupils=roster.read(room);
            context.put("roster",pupils);
            String previous=store.previousDraft(teacher,room.getId());
            Content previousContent=previous==null?null:mapper.readValue(previous,Content.class);
            if(previousContent!=null) context.put("lastGeneratedWorksheet",previousContent);
            int requiredCount=requestedCount(question,previousContent==null?0:previousContent.tasks().size());
            context.put("requestedTaskCount",requiredCount);
            if(request.context()!=null && request.context().studentId()!=null)
                context.put("selectedStudentId",request.context().studentId());
            else if(room.getStudent()!=null) context.put("selectedStudentId",room.getStudent().getId());
            Content content=mapper.readValue(ai.kolinStructuredReply(mapper.writeValueAsString(context),history,question),Content.class);
            if(content==null || content.kind()==null || content.text()==null || content.text().length()>4000
                    || content.tasks()==null) throw new IllegalArgumentException("Invalid Kolin content");
            if("text".equals(content.kind())) {
                if(!content.tasks().isEmpty() || content.studentId()!=null || content.text().isBlank())
                    throw new IllegalArgumentException("Invalid Kolin text reply");
                return store.complete(teacher,room.getId(),reserved.id(),reserved.attemptId(),"text",content.text(),transcript,
                        null,null,0,0,null,null);
            }
            if(!"worksheet".equals(content.kind()) || content.tasks().isEmpty() || content.tasks().size()>20)
                throw new IllegalArgumentException("Invalid Kolin worksheet");
            if(content.tasks().size()!=requiredCount)
                throw new IllegalArgumentException("Unexpected worksheet task count");
            UUID target=UUID.fromString(content.studentId());
            var pupil=pupils.stream().filter(item -> item.id().equals(target)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown worksheet recipient"));
            String document=mapper.writeValueAsString(document(content.tasks()));
            long started=System.nanoTime();
            var rendered=renderer.renderPlain(document);
            var verifiedContent=withVerifiedAnswers(content,rendered.qa());
            String summary=content.text().isBlank()?"Подготовлено "+content.tasks().size()+" заданий.":content.text();
            var result=store.complete(teacher,room.getId(),reserved.id(),reserved.attemptId(),"worksheet",summary,transcript,
                    pupil.id(),pupil.name(),content.tasks().size(),rendered.pageCount(),rendered.pdf(),rendered.previewPng(),mapper.writeValueAsString(verifiedContent));
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Kolin worksheet ready: classId={} artifactId={} tasks={} pages={} renderMs={}",
                    room.getId(),reserved.id(),content.tasks().size(),rendered.pageCount(),(System.nanoTime()-started)/1_000_000);
            return result;
        } catch(Exception ex) {
            store.fail(teacher,room.getId(),reserved.id(),reserved.attemptId());
            throw ex;
        }
    }

    /** Validation assertions are built from the exact expressions displayed to pupils. */
    static Map<String,Object> document(List<Task> tasks) {
        var pages=new ArrayList<Map<String,Object>>();
        var checks=new ArrayList<Map<String,Object>>();
        for(int offset=0;offset<tasks.size();offset+=5) {
            var rows=new ArrayList<Map<String,Object>>();
            for(int i=offset;i<Math.min(offset+5,tasks.size());i++) {
                Task task=tasks.get(i);
                requireText(task.instruction(),160); requireText(task.expression(),500);
                if(task.instruction().codePoints().anyMatch(value -> Character.isDigit(value) || "=<>+*/^\\$".indexOf(value)>=0))
                    throw new IllegalArgumentException("Math belongs in the checked expression field");
                if(task.restrictions()==null || task.restrictions().size()>4) throw new IllegalArgumentException("Invalid restrictions");
                task.restrictions().forEach(value -> requireText(value,60));
                Map<String,Object> check;
                if("expression".equals(task.kind())) {
                    if(task.expression().contains("=")) throw new IllegalArgumentException("Expression contains a hidden answer");
                    check=Map.of("kind","expressionValue","expression",task.expression(),"restrictions",task.restrictions());
                } else if("equation".equals(task.kind()) && Set.of("x","y","z","a","b","c","m","n").contains(task.variable())) {
                    check=Map.of("kind","linearValue","equation",task.expression(),"variable",task.variable(),
                            "restrictions",task.restrictions());
                } else throw new IllegalArgumentException("Unsupported checked math task");
                checks.add(check);
                rows.add(Map.of("label",String.valueOf(i+1)+".","math",Map.of("latex",task.expression()),
                        "text",task.instruction()+(task.restrictions().isEmpty()?"":" ("+String.join(", ",task.restrictions())+")"),"work",false));
            }
            pages.add(Map.of("title","","subtitle","","blocks",List.of(Map.of("type","exercise","number","",
                    "title","","subtitle","","rows",rows,"writeLines",0))));
        }
        return Map.of("meta",Map.of("school","MINDCRAFTI SCHOOL","durationMinutes",30,"group","","date",""),
                "workbookPages",pages,"teacherPages",List.of(Map.of("title","","subtitle","","blocks",List.of(
                    Map.of("type","text","text","Private automated answer validation")))),"qa",Map.of("algebra",checks));
    }
    private static void requireText(String value,int maximum) {
        if(value==null || value.isBlank() || value.length()>maximum) throw new IllegalArgumentException("Invalid task text");
    }
    static Content withVerifiedAnswers(Content content,Map<String,Object> qa) {
        if(!(qa.get("results") instanceof List<?> results) || results.size()!=content.tasks().size())
            throw new IllegalArgumentException("Missing computed reference answers");
        var tasks=new ArrayList<Task>();
        for(int i=0;i<results.size();i++) {
            if(!(results.get(i) instanceof Map<?,?> row) || !Boolean.TRUE.equals(row.get("passed"))
                    || !(row.get("answer") instanceof String answer) || answer.isBlank() || answer.length()>2000)
                throw new IllegalArgumentException("Invalid computed reference answer");
            var task=content.tasks().get(i);
            tasks.add(new Task(task.kind(),task.instruction(),task.expression(),answer,task.variable(),task.restrictions()));
        }
        return new Content(content.kind(),content.text(),content.studentId(),tasks);
    }
    static int requestedCount(String question) {
        return requestedCount(question,0);
    }
    static int requestedCount(String question,int previousCount) {
        var numeric=java.util.regex.Pattern.compile("(?iu)(\\d{1,3})\\s*(?:задан|пример|упражнен|aufgaben|aufgabe|tasks?|exercises?)").matcher(question);
        if(numeric.find()) return Integer.parseInt(numeric.group(1));
        var words=Map.ofEntries(Map.entry("одно",1),Map.entry("один",1),Map.entry("одну",1),Map.entry("два",2),Map.entry("две",2),
                Map.entry("три",3),Map.entry("четыре",4),Map.entry("пять",5),Map.entry("шесть",6),Map.entry("семь",7),
                Map.entry("восемь",8),Map.entry("девять",9),Map.entry("десять",10),Map.entry("one",1),Map.entry("two",2),
                Map.entry("three",3),Map.entry("four",4),Map.entry("five",5),Map.entry("eine",1),Map.entry("zwei",2),
                Map.entry("drei",3),Map.entry("vier",4),Map.entry("fünf",5));
        var verbal=java.util.regex.Pattern.compile("(?iu)([\\p{L}]+)\\s+(?:задан|пример|упражнен|aufgaben|aufgabe|tasks?|exercises?)").matcher(question);
        while(verbal.find()) { Integer count=words.get(verbal.group(1).toLowerCase(Locale.ROOT)); if(count!=null) return count; }
        if(previousCount>0 && !question.matches("(?isU).*\\b(?:пару|несколько|paar|einige)\\b.*")
                && question.matches("(?isu).*(?:втор|перв|треть|четв|пят|измени|передел|услож|упрост|сложнее|проще|schwerer|leichter|ändern).*"))
            return previousCount;
        return 5;
    }
}
