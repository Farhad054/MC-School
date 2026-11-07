package com.mcschool.flashcard.liveclasses;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.*;
import com.mcschool.flashcard.lessons.PersonalLessonWorkbookService;
import com.mcschool.flashcard.users.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

class KolinWorksheetStoreTest {
    private final KolinWorksheetRepository repository=mock(KolinWorksheetRepository.class);
    private final OnlineClassAccessService access=mock(OnlineClassAccessService.class);
    private final PersonalLessonWorkbookService personal=mock(PersonalLessonWorkbookService.class);
    private final KolinWorkbookAppendService append=mock(KolinWorkbookAppendService.class);
    private final JsonMapper mapper=JsonMapper.builder().build();
    private final KolinWorksheetStore store=new KolinWorksheetStore(repository,access,personal,append,mapper);
    private final User teacher=User.activeTeacher("Teacher","teacher@example.test","teacher","hash");
    private final User pupil=User.managedStudent("Виталина",teacher);
    private final AuthenticatedUser host=new AuthenticatedUser(teacher.getId(),teacher.getEmail(),Role.TEACHER);
    private final OnlineClass room=OnlineClass.forStudent(teacher,"event","event","Math",Instant.now(),Instant.now().plusSeconds(3600),pupil);
    private final UUID id=UUID.randomUUID(),client=UUID.randomUUID();
    private KolinWorksheet row;
    @BeforeEach void setup() throws Exception {
        row=new KolinWorksheet();
        ReflectionTestUtils.setField(row,"id",id); ReflectionTestUtils.setField(row,"classId",room.getId());
        ReflectionTestUtils.setField(row,"teacherId",teacher.getId()); ReflectionTestUtils.setField(row,"clientMessageId",client);
        ReflectionTestUtils.setField(row,"requestHash","hash"); ReflectionTestUtils.setField(row,"state","NEW");
        ReflectionTestUtils.setField(row,"updatedAt",Instant.now());
        when(access.requireHost(host,room.getId())).thenReturn(room);
        when(repository.findRequestForUpdate(room.getId(),teacher.getId(),client)).thenReturn(Optional.of(row));
        when(repository.findForUpdate(id,room.getId(),teacher.getId())).thenReturn(Optional.of(row));
        when(repository.findByIdAndClassIdAndTeacherId(id,room.getId(),teacher.getId())).thenReturn(Optional.of(row));
    }
    private void ready() throws Exception {
        row.complete("worksheet","Дроби",mapper.writeValueAsString(KolinTranscriptContextService.unavailable(room).metadata()),
                pupil.getId(),"Виталина",5,1,new byte[]{1,2,3},new byte[]{4,5});
    }
    @Test void reservesRequestAndRejectsChangedPayloadOrConcurrentGeneration() {
        var reserved=store.begin(host,room.getId(),client,"hash");
        assertThat(reserved.id()).isEqualTo(id); assertThat(reserved.attemptId()).isNotNull();
        assertThat(row.getState()).isEqualTo("GENERATING");
        assertThatThrownBy(()->store.begin(host,room.getId(),client,"changed")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->store.begin(host,room.getId(),client,"hash")).isInstanceOf(ConflictException.class);
    }
    @Test void originalDraftIsReturnedOnRetryWithoutMutatingWorkbook() throws Exception {
        ready();
        var result=store.begin(host,room.getId(),client,"hash");
        assertThat(result.cached().worksheet().id()).isEqualTo(id);
        assertThat(result.cached().worksheet().state()).isEqualTo("READY");
        verify(personal).requireClassStudent(room,pupil.getId()); verifyNoInteractions(append);
    }
    @Test void failedRequestsCanBeRetriedAndStaleWorkerCannotOverwriteANewerAttempt() {
        var first=store.begin(host,room.getId(),client,"hash");
        store.fail(host,room.getId(),id,first.attemptId());
        var second=store.begin(host,room.getId(),client,"hash");
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        store.fail(host,room.getId(),id,first.attemptId());
        assertThat(row.getState()).isEqualTo("GENERATING");
        assertThatThrownBy(()->store.complete(host,room.getId(),id,first.attemptId(),"text","Old",null,null,null,0,0,null,null))
                .isInstanceOf(ConflictException.class);
    }
    @Test void confirmingTwiceAppendsExactlyOnceAndReturnsTheSamePageRange() throws Exception {
        ready();
        when(append.append(host,room,pupil.getId(),row.getPdfBytes())).thenReturn(new KolinWorkbookAppendService.Result(4,4,2));
        var first=store.append(host,room.getId(),id);
        var second=store.append(host,room.getId(),id);
        assertThat(first).isEqualTo(second); assertThat(first.state()).isEqualTo("ADDED");
        assertThat(first.fromPage()).isEqualTo(4); assertThat(first.toPage()).isEqualTo(4);
        verify(append,times(1)).append(host,room,pupil.getId(),row.getPdfBytes());
    }
    @Test void openingPreviewOrPdfNeverAppendsAndRevokedPupilCannotBeReadOrConfirmed() throws Exception {
        ready();
        assertThat(store.file(host,room.getId(),id,true)).containsExactly(4,5);
        assertThat(store.file(host,room.getId(),id,false)).containsExactly(1,2,3);
        verifyNoInteractions(append);
        doThrow(new ResourceNotFoundException("not in class")).when(personal).requireClassStudent(room,pupil.getId());
        assertThatThrownBy(()->store.file(host,room.getId(),id,true)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->store.append(host,room.getId(),id)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(append);
    }
    @Test void otherTeacherOrRoomCannotReadArtifactAndEndedLessonCannotBeChanged() throws Exception {
        ready();
        UUID other=UUID.randomUUID();
        when(access.requireHost(host,other)).thenThrow(new ResourceNotFoundException("not found"));
        assertThatThrownBy(()->store.file(host,other,id,true)).isInstanceOf(ResourceNotFoundException.class);
        ReflectionTestUtils.setField(room,"status",OnlineClassStatus.ENDED);
        assertThatThrownBy(()->store.append(host,room.getId(),id)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(append);
    }
}
