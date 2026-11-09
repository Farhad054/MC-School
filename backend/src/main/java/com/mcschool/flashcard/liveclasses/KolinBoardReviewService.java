package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Service;

/** A teacher-requested view of one pupil page, never a background recording. */
@Service
public class KolinBoardReviewService {
    public static final int MAX_IMAGE_BYTES = 1_500_000;
    private static final String JPEG = "data:image/jpeg;base64,";
    private static final Pattern REVIEW = Pattern.compile("(?iu)(провер[ьиья]|проверь|посмотр|разбер|разоб[ер]|оцени|неправиль|ошиб|что.{0,20}реш|как.{0,20}реш|check|review|mistake|wrong|prüf|pruf|kontrollier|schau|fehler)");
    private static final Pattern WHOLE = Pattern.compile("(?iu)(всю|весь|всех.{0,10}страниц|тетрад|документ|неправиль|ошиб|что.{0,20}реш|как.{0,20}реш|workbook|document|all.{0,10}page|mistake|wrong|fehler|heft|gesamte)");
    private static final Pattern WORK = Pattern.compile("(?iu)(задан|решени|реша|доск|страниц|тетрад|работ|ошиб|напис|правиль|aufgab|lös|losung|seite|tafel|heft|arbeit|fehler|work|page|board|solution|answer)");
    private final OnlineClassAccessService access;
    private final KolinTaskRoster roster;
    private final OnlineClassDocumentService documents;

    public KolinBoardReviewService(OnlineClassAccessService access, KolinTaskRoster roster, OnlineClassDocumentService documents) {
        this.access=access; this.roster=roster; this.documents=documents;
    }
    public record TargetRequest(@NotBlank @Size(max=4000) String text, @jakarta.validation.Valid KolinChatService.Context context) {}
    public record Target(String state, UUID studentId, String studentName, String message,String scope) {
        public Target(String state,UUID id,String name,String message) { this(state,id,name,message,"current_page"); }
    }
    public record Page(int pageNumber,long revision,@Size(max=2000022) String imageDataUrl) {}
    public record Snapshot(UUID studentId, String boardId, int pageNumber, long documentVersion, long revision,
                           Instant observedAt, Instant capturedAt, @Size(max=2000022) String imageDataUrl,
                           String scope,int totalPageCount,@Size(max=39) List<@jakarta.validation.Valid Page> additionalPages) {
        public Snapshot(UUID id,String board,int page,long version,long revision,Instant observed,Instant captured,String image) {
            this(id,board,page,version,revision,observed,captured,image,"current_page",page,List.of());
        }
    }
    public record PageMetadata(int pageNumber,long revision,String imageSha256) {}
    public record Metadata(UUID studentId, String studentName, String boardId, int pageNumber,
                           long documentVersion, long revision, Instant capturedAt, String imageSha256,
                           String scope,int totalPageCount,List<PageMetadata> pages) {}

    public Target resolve(AuthenticatedUser teacher, UUID classId, TargetRequest request) {
        return resolve(access.requireHost(teacher,classId),request.text(),request.context());
    }
    Target resolve(OnlineClass room, String text, KolinChatService.Context context) {
        if (!REVIEW.matcher(text).find()) return new Target("not_requested",null,null,null);
        if(Pattern.compile("(?iu)(транскрип|расшифров|сказал|произнес|gesagt|transcript|said)").matcher(text).find()
                && !Pattern.compile("(?iu)(доск|тетрад|страниц|board|page|heft|seite)").matcher(text).find())
            return new Target("not_requested",null,null,null);
        if(Pattern.compile("(?iu)^\\s*(дай|создай|сделай|подготовь|erstelle|create|generate).*(задан|pdf|интерактив|aufgab|worksheet)").matcher(text).find())
            return new Target("not_requested",null,null,null);
        String scope=WHOLE.matcher(text).find()?"workbook":"current_page";
        var pupils=roster.read(room);
        var words=Arrays.stream(normalize(text).split("[^a-z0-9]+")).filter(s->!s.isBlank()).toList();
        var matches=pupils.stream().filter(p->Arrays.stream(normalize(p.name()).split("[^a-z]+")).filter(n->n.length()>=3)
                .anyMatch(name->words.stream().anyMatch(word->nameMatches(name,word)))).toList();
        // Unicode word boundaries changed in Java 19. Keep Cyrillic names identical on Java 17/21.
        boolean explicit=Pattern.compile("(?iuU)\\b(у|для|von|bei|for|of)\\s+\\p{L}+").matcher(text).find()
                || hasUnresolvedRecipient(text);
        if(matches.isEmpty() && !explicit && !Pattern.compile("(?iu)(проверь|посмотри|разбери|оцени|check|review|prüf|kontrollier|schau)").matcher(text).find())
            return new Target("not_requested",null,null,null);
        if (matches.isEmpty() && !WORK.matcher(text).find() && !explicit)
            return new Target("not_requested",null,null,null);
        if (matches.isEmpty() && !explicit && text.matches("(?s).*\\d.*")
                && !Pattern.compile("(?iu)(доск|страниц|тетрад|учени|tafel|seite|heft|schüler|board|page|pupil)").matcher(text).find())
            return new Target("not_requested",null,null,null);
        if (matches.size()==1) return ready(matches.get(0),scope);
        if (matches.size()>1 || explicit) return clarify();
        UUID selected=context==null?null:context.studentId();
        if(selected==null && room.getStudent()!=null) selected=room.getStudent().getId();
        UUID id=selected;
        var pupil=pupils.stream().filter(p->p.id().equals(id)).findFirst();
        return pupil.map(p->ready(p,scope)).orElseGet(KolinBoardReviewService::clarify);
    }
    private static Target ready(KolinTaskRoster.Pupil pupil,String scope) { return new Target("ready",pupil.id(),pupil.name(),null,scope); }
    private static Target clarify() { return new Target("clarify",null,null,
            "Уточните имя одного ученика из этого урока или откройте его личную доску и попросите проверить решение."); }

    // Do not treat an unfamiliar name after a review verb/work noun as the selected pupil.
    private static boolean hasUnresolvedRecipient(String text) {
        var mentions=Pattern.compile("(?iuU)\\b(?:проверь|посмотри|разбери|оцени|задани[ея]|решени[ея]|работ[уыа]|тетрад[ьи])(?=\\s+(\\p{L}+))").matcher(text);
        var generic=Set.of("пожалуйста","сейчас","заодно","ещё","еще","тоже","у","для","на","в","из","с","по","и",
                "это","этот","эту","эти","текущее","текущую","всю","весь","все","всё","его","её","ее","мое","моё","мои",
                "решение","решения","задание","задания","работу","работы","тетрадь","страницу","страницы","ответ","ответы",
                "вычисления","правильность","полностью","целиком","ниже","выше","что","как","верно","правильно");
        while(mentions.find()) if(!generic.contains(mentions.group(1).toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // Exact names plus common case endings. No fuzzy nearest-pupil selection.
    static boolean nameMatches(String name,String word) {
        if(name.equals(word)) return true;
        String stem=name.replaceFirst("(?:iya|ia|ya|a)$","");
        return stem.length()>=3 && word.startsWith(stem)
                && Set.of("a","y","e","u","i","ia","ya","yi","ii","iyu","yu","s","as","yas","ias").contains(word.substring(stem.length()));
    }
    static String normalize(String text) {
        String value=Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё','е'),Normalizer.Form.NFD).replaceAll("\\p{M}","");
        String from="абвгдеэийклмнопрстуфхыьъ",to="abvgdeeijklmnoprstufhy";
        var out=new StringBuilder();
        for(char c:value.toCharArray()) {
            int at=from.indexOf(c);
            if(at>=0) { if(at<to.length()) out.append(to.charAt(at)); }
            else out.append(switch(c) { case 'ж'->"zh";case 'з'->"z";case 'ц'->"ts";case 'ч'->"ch";case 'ш'->"sh";case 'щ'->"sch";case 'ю'->"yu";case 'я'->"ya";default->String.valueOf(c); });
        }
        return out.toString();
    }

    /** Re-check the authorized recipient independently of any browser-supplied metadata. */
    public Metadata validate(OnlineClass room, String text, KolinChatService.Context context, Snapshot snapshot) {
        var target=resolve(room,text,context);
        if(!"ready".equals(target.state()) || snapshot==null || !Objects.equals(target.studentId(),snapshot.studentId())
                || !PersonalBoard.ownerOf(snapshot.boardId()).filter(target.studentId()::equals).isPresent()
                || snapshot.pageNumber()<1 || snapshot.pageNumber()>1000 || snapshot.documentVersion()<0 || snapshot.revision()<0
                || snapshot.capturedAt()==null || !Objects.equals(target.scope(),snapshot.scope())
                || snapshot.totalPageCount()<snapshot.pageNumber() || snapshot.totalPageCount()>1000
                || !"workbook".equals(snapshot.scope()) && (snapshot.observedAt()==null
                    || snapshot.observedAt().isAfter(snapshot.capturedAt().plusSeconds(5))
                    || snapshot.observedAt().isBefore(snapshot.capturedAt().minusSeconds(30)))) throw KolinRequestException.reviewUnavailable();
        var pages=new ArrayList<Page>();pages.add(new Page(snapshot.pageNumber(),snapshot.revision(),snapshot.imageDataUrl()));
        if(snapshot.additionalPages()!=null) pages.addAll(snapshot.additionalPages());
        if(pages.size()>40 || !"workbook".equals(snapshot.scope()) && pages.size()!=1
                || "workbook".equals(snapshot.scope()) && pages.size()!=snapshot.totalPageCount()
                || pages.stream().anyMatch(p->p==null || p.pageNumber()<1 || p.pageNumber()>snapshot.totalPageCount() || p.revision()<0)
                || pages.stream().map(Page::pageNumber).distinct().count()!=pages.size()
                || pages.stream().mapToLong(p->p.imageDataUrl()==null?Long.MAX_VALUE/100:p.imageDataUrl().length()).sum()>16_000_000)
            throw KolinRequestException.reviewUnavailable();
        var metadata=pages.stream().map(p->new PageMetadata(p.pageNumber(),p.revision(),imageHash(p.imageDataUrl()))).toList();
        return new Metadata(target.studentId(),target.studentName(),snapshot.boardId(),snapshot.pageNumber(),snapshot.documentVersion(),
                snapshot.revision(),snapshot.capturedAt(),metadata.get(0).imageSha256(),snapshot.scope(),snapshot.totalPageCount(),metadata);
    }
    private String imageHash(String data) {
        if(data==null || !data.startsWith(JPEG) || data.length()>2_000_022) throw KolinRequestException.reviewUnavailable();
        try {
            byte[] bytes=Base64.getDecoder().decode(data.substring(JPEG.length()));
            if(bytes.length>MAX_IMAGE_BYTES || bytes.length<4 || (bytes[0]&255)!=255 || (bytes[1]&255)!=216) throw new IllegalArgumentException();
            try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(input);
                if(!readers.hasNext()) throw new IllegalArgumentException();
                var reader=readers.next();
                try {
                    if(!"JPEG".equalsIgnoreCase(reader.getFormatName())) throw new IllegalArgumentException();
                    reader.setInput(input);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<32 || height<32 || width>3072 || height>3072 || (long)width*height>6_000_000 || reader.read(0)==null)
                        throw new IllegalArgumentException();
                } finally { reader.dispose(); }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(Exception ex) { throw KolinRequestException.reviewUnavailable(); }
    }

    /** Only fresh model calls need a current source; cached answers retain their original dated snapshot. */
    public void requireCurrent(AuthenticatedUser teacher, UUID classId, Snapshot snapshot) {
        Instant now=Instant.now();
        if(snapshot.capturedAt().isBefore(now.minusSeconds(300)) || snapshot.capturedAt().isAfter(now.plusSeconds(60)))
            throw KolinRequestException.reviewUnavailable();
        long personal=documents.workbookVersion(teacher,classId,PersonalBoard.workbookTargetIdFor(snapshot.studentId()));
        String expected=personal>0?PersonalBoard.workbookTargetIdFor(snapshot.studentId()):PersonalBoard.targetIdFor(snapshot.studentId());
        long version=personal>0?personal:documents.workbookVersion(teacher,classId,expected);
        if(!expected.equals(snapshot.boardId()) || version!=snapshot.documentVersion()) throw KolinRequestException.reviewUnavailable();
    }
}
