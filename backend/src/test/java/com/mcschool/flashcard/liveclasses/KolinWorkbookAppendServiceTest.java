package com.mcschool.flashcard.liveclasses;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.lessons.*;
import com.mcschool.flashcard.users.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class KolinWorkbookAppendServiceTest {
    private final OnlineClassAccessService access=mock(OnlineClassAccessService.class);
    private final PersonalLessonWorkbookService personal=mock(PersonalLessonWorkbookService.class);
    private final PersonalLessonWorkbookRepository workbooks=mock(PersonalLessonWorkbookRepository.class);
    private final LessonPreparationRepository preparations=mock(LessonPreparationRepository.class);
    private final OnlineClassAnnotationDocumentRepository documents=mock(OnlineClassAnnotationDocumentRepository.class);
    private final EntityManager entities=mock(EntityManager.class);
    private final KolinWorkbookAppendService service=new KolinWorkbookAppendService(access,personal,workbooks,preparations,documents,entities);
    private final User teacher=User.activeTeacher("Teacher","teacher@test.local","teacher","unused");
    private final User student=User.managedStudent("Pupil",teacher);
    private final AuthenticatedUser host=new AuthenticatedUser(teacher.getId(),null,teacher.getRole());
    private final OnlineClass room=OnlineClass.forStudent(teacher,"event","event","Lesson",Instant.now(),Instant.now().plusSeconds(3600),student);

    @BeforeEach void setup(){
        when(access.requireHostForUpdate(host,room.getId())).thenReturn(room);
        when(access.isHost(host,room)).thenReturn(true);
        when(personal.enabledForTeacher(teacher.getId())).thenReturn(true);
        when(workbooks.findByTeacherIdAndEventIdAndStudentId(teacher.getId(),"event",student.getId())).thenReturn(Optional.empty());
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId())).thenReturn(List.of());
        when(preparations.findByTeacherIdAndEventId(teacher.getId(),"event"))
                .thenReturn(Optional.of(LessonPreparation.create(teacher,"event")));
        when(workbooks.saveAndFlush(any())).thenAnswer(call->{PersonalLessonWorkbook row=call.getArgument(0);
            ReflectionTestUtils.setField(row,"version",row.getVersion()==null?0L:row.getVersion()+1L);return row;});
    }

    private OnlineClassAnnotationDocument page(String target,int index){
        return OnlineClassAnnotationDocument.create(room,AnnotationTargetType.WHITEBOARD,target,index);
    }
    private byte[] pdf(String... texts)throws Exception{
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
            for(String text:texts){var page=new PDPage();doc.addPage(page);if(!text.isEmpty())try(var stream=new PDPageContentStream(doc,page)){
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);stream.newLineAtOffset(30,700);stream.showText(text);stream.endText();}}
            doc.save(out);return out.toByteArray();
        }
    }
    private byte[] savedPdf(){var saved=ArgumentCaptor.forClass(PersonalLessonWorkbook.class);verify(workbooks).saveAndFlush(saved.capture());return saved.getValue().getWorkbookPdf();}

    @Test void firstPrivateAppendPreservesSharedBackgroundInkAndBlankPageExtentWithoutTouchingOtherScopes()throws Exception{
        byte[] shared=pdf("shared page 1","shared page 2");
        var prep=LessonPreparation.create(teacher,"event");prep.attachWorkbook("shared.pdf",shared);
        when(preparations.findByTeacherIdAndEventId(teacher.getId(),"event")).thenReturn(Optional.of(prep));
        var ink=page(PersonalBoard.targetIdFor(student.getId()),0);ink.nextRevision();
        var blank=page(ink.getTargetId(),4);blank.describeSource(800,600);
        var sharedBoard=page("board-1",40);
        var other=page(PersonalBoard.targetIdFor(java.util.UUID.randomUUID()),80);
        var camera=OnlineClassAnnotationDocument.create(room,AnnotationTargetType.NOTEBOOK_CAMERA,ink.getTargetId(),90);
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId())).thenReturn(List.of(ink,blank,sharedBoard,other,camera));
        var id=ink.getId();var result=service.append(host,room,student.getId(),pdf("new task 1","new task 2"));
        assertThat(result).isEqualTo(new KolinWorkbookAppendService.Result(6,7,1));
        assertThat(ink.getId()).isEqualTo(id);assertThat(ink.getRevision()).isEqualTo(1);
        assertThat(ink.getTargetId()).isEqualTo(PersonalBoard.workbookTargetIdFor(student.getId()));
        assertThat(blank.getTargetId()).isEqualTo(ink.getTargetId());
        assertThat(sharedBoard.getTargetId()).isEqualTo("board-1");assertThat(other.getTargetId()).startsWith("personal-");
        assertThat(camera.getTargetId()).isEqualTo(PersonalBoard.targetIdFor(student.getId()));
        assertThat(prep.getWorkbookPdf()).isEqualTo(shared);
        try(var merged=Loader.loadPDF(savedPdf())){
            assertThat(merged.getNumberOfPages()).isEqualTo(7);
            assertThat(new PDFTextStripper().getText(merged)).contains("shared page 1","shared page 2","new task 1","new task 2");
            assertThat(merged.getPage(4).getMediaBox().getWidth()/merged.getPage(4).getMediaBox().getHeight()).isEqualTo(800f/600f);
        }
        verify(entities).refresh(room,LockModeType.PESSIMISTIC_WRITE);
        verify(personal).requireClassStudent(room,student.getId());
    }

    @Test void existingPrivatePdfPadsBlankPagesAndKeepsItsScopeAndFilename()throws Exception{
        var stored=PersonalLessonWorkbook.create(teacher.getId(),"event",student.getId());stored.attach("mine.pdf",pdf("old private"));
        ReflectionTestUtils.setField(stored,"version",3L);
        when(workbooks.findByTeacherIdAndEventIdAndStudentId(teacher.getId(),"event",student.getId())).thenReturn(Optional.of(stored));
        var privateInk=page(PersonalBoard.workbookTargetIdFor(student.getId()),2);
        var legacy=page(PersonalBoard.targetIdFor(student.getId()),9);
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId())).thenReturn(List.of(privateInk,legacy));
        assertThat(service.append(host,room,student.getId(),pdf("new private")))
                .isEqualTo(new KolinWorkbookAppendService.Result(4,4,5));
        assertThat(stored.getFilename()).isEqualTo("mine.pdf");assertThat(legacy.getTargetId()).isEqualTo(PersonalBoard.targetIdFor(student.getId()));
        try(var merged=Loader.loadPDF(stored.getWorkbookPdf())){assertThat(merged.getNumberOfPages()).isEqualTo(4);assertThat(new PDFTextStripper().getText(merged)).contains("old private","new private");}
        verifyNoInteractions(preparations);verify(documents,never()).flush();
    }

    @Test void untouchedBoardStartsWithTheGeneratedTaskWithoutAnInventedBlankPage()throws Exception{
        assertThat(service.append(host,room,student.getId(),pdf("first task")).fromPage()).isEqualTo(1);
        try(var doc=Loader.loadPDF(savedPdf())){assertThat(doc.getNumberOfPages()).isEqualTo(1);}
    }

    @Test void foreignHostAndPupilOutsideCurrentRosterAreRejectedBeforePdfReads()throws Exception{
        byte[] generated=pdf("task");
        when(access.isHost(host,room)).thenReturn(false);
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workbooks,preparations,documents);
        when(access.isHost(host,room)).thenReturn(true);
        doThrow(new ResourceNotFoundException("Not in room")).when(personal).requireClassStudent(room,student.getId());
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(workbooks,preparations,documents);
    }

    @Test void endedClassCannotAppend()throws Exception{
        room.start(Instant.now());room.end(Instant.now());byte[] generated=pdf("task");
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class).hasMessageContaining("ended");
        verifyNoInteractions(personal,workbooks,preparations,documents);
    }

    @Test void unsafeDualScopeWithoutPrivatePdfFailsClosed()throws Exception{
        var legacy=page(PersonalBoard.targetIdFor(student.getId()),0);
        var orphan=page(PersonalBoard.workbookTargetIdFor(student.getId()),0);
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId())).thenReturn(List.of(legacy,orphan));
        byte[] generated=pdf("task");
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class).hasMessageContaining("scope");
        assertThat(legacy.getTargetId()).isEqualTo(PersonalBoard.targetIdFor(student.getId()));
        verify(workbooks,never()).saveAndFlush(any());
    }

    @Test void corruptOversizedOrExcessiveGeneratedPdfCannotReplaceWorkbook()throws Exception{
        byte[] many=pdf(java.util.Collections.nCopies(11,"").toArray(String[]::new));
        for(byte[] generated:List.of("%PDF-broken".getBytes(),new byte[26*1024*1024],many))
            assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class);
        verify(workbooks,never()).saveAndFlush(any());
    }

    @Test void excessiveBlankPageExtentFailsBeforeMutation()throws Exception{
        var far=page(PersonalBoard.targetIdFor(student.getId()),1000);
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId())).thenReturn(List.of(far));byte[] generated=pdf("task");
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class);
        assertThat(far.getTargetId()).isEqualTo(PersonalBoard.targetIdFor(student.getId()));
        verify(workbooks,never()).saveAndFlush(any());
    }

    @Test void maliciousMaximumPageIndexCannotWrapAndOverwriteTheExistingBoard()throws Exception{
        var far=page(PersonalBoard.targetIdFor(student.getId()),Integer.MAX_VALUE);
        when(documents.findAllByOnlineClassIdOrderByPageIndexAsc(room.getId()))
                .thenReturn(List.of(page(far.getTargetId(),0),far));byte[] generated=pdf("task");
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class);
        assertThat(far.getTargetId()).isEqualTo(PersonalBoard.targetIdFor(student.getId()));
        verify(workbooks,never()).saveAndFlush(any());
    }

    @Test void optimisticWorkbookConflictIsAReviewableFailure()throws Exception{
        doThrow(new ObjectOptimisticLockingFailureException(PersonalLessonWorkbook.class,"id")).when(workbooks).saveAndFlush(any());byte[] generated=pdf("task");
        assertThatThrownBy(()->service.append(host,room,student.getId(),generated)).isInstanceOf(ConflictException.class).hasMessageContaining("changed");
    }
}
