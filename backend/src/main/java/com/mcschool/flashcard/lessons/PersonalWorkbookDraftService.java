package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import java.util.*;
import org.springframework.stereotype.Service;

/** Copies already reviewed server PDFs directly; no ChatGPT file transport or download URL required. */
@Service
public class PersonalWorkbookDraftService {
    private final McpAccessService access;
    private final McpOperationReceiptService receipts;
    private final LessonPdfDraftService drafts;
    private final PersonalLessonWorkbookService workbooks;
    private final PersonalLessonAnswersService answers;
    public PersonalWorkbookDraftService(McpAccessService access,McpOperationReceiptService receipts,
            LessonPdfDraftService drafts,PersonalLessonWorkbookService workbooks,PersonalLessonAnswersService answers) {
        this.access=access;this.receipts=receipts;this.drafts=drafts;this.workbooks=workbooks;this.answers=answers;
    }
    public Object publish(AuthenticatedUser actor,UUID teacherId,String eventId,UUID studentId,UUID draftId,String key,boolean confirm) {
        if(!confirm)throw new IllegalArgumentException("Review both PDFs and supply confirm=true only after the user's explicit upload instruction");
        AuthenticatedUser teacher=access.requireTeacher(actor,teacherId);
        Map<String,Object> normalized=Map.of("teacherId",teacher.id().toString(),"eventId",eventId,"studentId",studentId.toString(),"draftId",draftId.toString());
        return receipts.execute(actor,teacher.id(),"publish_personal_lesson_draft",key,normalized,
                ()->validate(actor,teacher.id(),eventId,studentId,draftId),
                ()->access.asTeacher(actor,teacher,()->{
                    var workbook=drafts.previewFile(teacher,draftId,"workbook");
                    var solution=drafts.previewFile(teacher,draftId,"answers");
                    workbooks.upload(teacher,eventId,studentId,workbook.filename(),workbook.bytes());
                    answers.upload(teacher,eventId,studentId,solution.filename(),solution.bytes());
                    var saved=workbooks.list(teacher,eventId).students().stream().filter(s->s.studentId().equals(studentId)).findFirst().orElseThrow();
                    if(!saved.hasWorkbook()||!saved.hasAnswers()||!workbook.filename().equals(saved.filename())
                            ||!solution.filename().equals(saved.answersFilename())||saved.workbookSizeBytes()!=workbook.bytes().length
                            ||saved.answersSizeBytes()!=solution.bytes().length)throw new IllegalStateException("Personal PDF readback did not match the approved draft");
                    return Map.of("published",true,"sourceDraftId",draftId.toString(),"teacherId",teacher.id().toString(),
                            "eventId",eventId,"studentId",studentId.toString(),"workbookFilename",saved.filename(),
                            "answersFilename",saved.answersFilename(),"workbookVersion",saved.version(),"answersVersion",saved.answersVersion(),
                            "sourceRetained",true);
                }));
    }
    private void validate(AuthenticatedUser actor,UUID teacherId,String eventId,UUID studentId,UUID draftId) {
        AuthenticatedUser teacher=access.requireTeacher(actor,teacherId);
        workbooks.requireLessonStudent(teacher,eventId,studentId);
        for(String kind:List.of("workbook","answers"))if(!eventId.equals(drafts.previewFile(teacher,draftId,kind).eventId()))
            throw new IllegalArgumentException("The draft belongs to a different lesson");
    }
}
