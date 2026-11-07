package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.lessons.PersonalLessonWorkbookService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Short transactions only. Model and renderer work happen outside these methods. */
@Service
public class KolinWorksheetStore {
    public record Worksheet(UUID id, UUID studentId, String studentName, int taskCount, int pageCount,
                            String state, Integer fromPage, Integer toPage) {}
    public record Reservation(UUID id, UUID attemptId, KolinChatService.Reply cached) {}
    private final KolinWorksheetRepository repository;
    private final OnlineClassAccessService access;
    private final PersonalLessonWorkbookService personal;
    private final KolinWorkbookAppendService append;
    private final ObjectMapper mapper;

    public KolinWorksheetStore(KolinWorksheetRepository repository, OnlineClassAccessService access,
            PersonalLessonWorkbookService personal, KolinWorkbookAppendService append, ObjectMapper mapper) {
        this.repository=repository; this.access=access; this.personal=personal; this.append=append; this.mapper=mapper;
    }

    @Transactional
    public Reservation begin(AuthenticatedUser teacher, UUID classId, UUID clientId, String hash) {
        OnlineClass room=access.requireHost(teacher,classId);
        repository.reserve(UUID.randomUUID(),classId,teacher.id(),clientId,hash);
        var row=repository.findRequestForUpdate(classId,teacher.id(),clientId).orElseThrow(this::missing);
        if (!hash.equals(row.getRequestHash())) throw new ConflictException("Kolin request changed; send a new message");
        if ("READY".equals(row.getState()) || "ADDED".equals(row.getState())) {
            checkPupil(room,row);
            return new Reservation(row.getId(),row.getAttemptId(),reply(row));
        }
        requireOpen(room);
        if ("GENERATING".equals(row.getState()) && row.getUpdatedAt().isAfter(Instant.now().minusSeconds(180)))
            throw new ConflictException("Kolin is still preparing this reply; retry the same message shortly");
        row.generating();
        repository.saveAndFlush(row);
        return new Reservation(row.getId(),row.getAttemptId(),null);
    }

    @Transactional
    public KolinChatService.Reply complete(AuthenticatedUser teacher, UUID classId, UUID id, UUID attemptId,
            String type, String text, KolinTranscriptContextService.Metadata metadata,
            UUID studentId, String studentName, int taskCount, int pageCount, byte[] pdf, byte[] preview) {
        return complete(teacher,classId,id,attemptId,type,text,metadata,studentId,studentName,taskCount,pageCount,pdf,preview,null);
    }

    @Transactional
    public KolinChatService.Reply complete(AuthenticatedUser teacher, UUID classId, UUID id, UUID attemptId,
            String type, String text, KolinTranscriptContextService.Metadata metadata,
            UUID studentId, String studentName, int taskCount, int pageCount, byte[] pdf, byte[] preview,String taskContent) {
        OnlineClass room=access.requireHost(teacher,classId);
        var row=repository.findForUpdate(id,classId,teacher.id()).orElseThrow(this::missing);
        if (!attemptId.equals(row.getAttemptId()) || !"GENERATING".equals(row.getState()))
            throw new ConflictException("Kolin request was retried; reload the result");
        requireOpen(room);
        if (studentId!=null) personal.requireClassStudent(room,studentId);
        try {
            row.complete(type,text,mapper.writeValueAsString(metadata),studentId,studentName,taskCount,pageCount,pdf,preview);
            row.rememberTasks(taskContent);
        } catch (Exception ex) { throw new IllegalStateException("Cannot store Kolin result",ex); }
        repository.saveAndFlush(row);
        return reply(row);
    }

    @Transactional(readOnly=true)
    public String previousDraft(AuthenticatedUser teacher,UUID classId) {
        var room=access.requireHost(teacher,classId);
        var rows=repository.latestDraftContext(classId,teacher.id(),org.springframework.data.domain.PageRequest.of(0,1));
        if(rows.isEmpty()) return null;
        var row=rows.get(0);
        try { personal.requireClassStudent(room,row.getStudentId()); }
        catch(ResourceNotFoundException ex) { return null; }
        return row.getTaskContent();
    }

    @Transactional
    public void fail(AuthenticatedUser teacher, UUID classId, UUID id, UUID attemptId) {
        repository.findForUpdate(id,classId,teacher.id()).filter(row -> attemptId.equals(row.getAttemptId())
                && "GENERATING".equals(row.getState())).ifPresent(row -> { row.failed(); repository.save(row); });
    }

    @Transactional(readOnly=true)
    public byte[] file(AuthenticatedUser teacher, UUID classId, UUID id, boolean preview) {
        OnlineClass room=access.requireHost(teacher,classId);
        var row=repository.findByIdAndClassIdAndTeacherId(id,classId,teacher.id()).orElseThrow(this::missing);
        requireWorksheet(row); checkPupil(room,row);
        return preview ? row.getPreviewBytes() : row.getPdfBytes();
    }

    @Transactional
    public Worksheet append(AuthenticatedUser teacher, UUID classId, UUID id) {
        OnlineClass room=access.requireHost(teacher,classId);
        var row=repository.findForUpdate(id,classId,teacher.id()).orElseThrow(this::missing);
        requireWorksheet(row); checkPupil(room,row);
        if ("ADDED".equals(row.getState())) return metadata(row);
        requireOpen(room);
        var result=append.append(teacher,room,row.getStudentId(),row.getPdfBytes());
        row.added(result.fromPage(),result.toPage());
        repository.saveAndFlush(row);
        return metadata(row);
    }

    private void checkPupil(OnlineClass room,KolinWorksheet row) {
        if (row.getStudentId()!=null) personal.requireClassStudent(room,row.getStudentId());
    }
    private void requireWorksheet(KolinWorksheet row) {
        if (!"worksheet".equals(row.getResultType()) || !java.util.List.of("READY","ADDED").contains(row.getState())
                || row.getStudentId()==null || row.getPdfBytes()==null || row.getPreviewBytes()==null) throw missing();
    }
    private static void requireOpen(OnlineClass room) {
        if (room.getStatus()==OnlineClassStatus.ENDED || room.getStatus()==OnlineClassStatus.CANCELLED)
            throw new ConflictException("Lesson has ended; its workbook cannot be changed");
    }
    private KolinChatService.Reply reply(KolinWorksheet row) {
        try {
            return new KolinChatService.Reply(row.getResultType(),row.getReplyText(),
                    mapper.readValue(row.getTranscriptMetadata(),KolinTranscriptContextService.Metadata.class),
                    "worksheet".equals(row.getResultType()) ? metadata(row) : null);
        } catch (Exception ex) { throw new IllegalStateException("Cannot read Kolin result",ex); }
    }
    private static Worksheet metadata(KolinWorksheet row) {
        return new Worksheet(row.getId(),row.getStudentId(),row.getStudentName(),row.getTaskCount(),row.getPageCount(),
                row.getState(),row.getFromPage(),row.getToPage());
    }
    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Kolin worksheet not found"); }
}
