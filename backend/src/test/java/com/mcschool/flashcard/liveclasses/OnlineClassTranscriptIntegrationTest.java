package com.mcschool.flashcard.liveclasses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcschool.flashcard.AbstractIntegrationTest;
import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.common.ConflictException;
import com.mcschool.flashcard.common.ResourceNotFoundException;
import com.mcschool.flashcard.lessons.LessonPreparationRepository;
import com.mcschool.flashcard.lessons.LessonPreparationService;
import com.mcschool.flashcard.lessons.dto.UpdateLessonPreparationRequest;
import com.mcschool.flashcard.liveclasses.dto.TranscriptSegmentRequest;
import com.mcschool.flashcard.liveclasses.dto.TranscriptSegmentResponse;
import com.mcschool.flashcard.liveclasses.provider.ClassTranscriptionProvider;
import com.mcschool.flashcard.liveclasses.provider.LiveClassMediaProvider;
import com.mcschool.flashcard.liveclasses.provider.MediaProviderException;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Transcription control, worker ingestion, exports and failure isolation. */
@TestPropertySource(properties = {
        "app.online-classes.enabled=true",
        "app.online-classes.livekit-url=wss://test.invalid",
        "app.online-classes.livekit-api-key=test-key",
        "app.online-classes.livekit-api-secret=test-secret",
        "app.online-classes.transcription-enabled=true",
        "app.online-classes.transcription-internal-token="
                + OnlineClassTranscriptIntegrationTest.INTERNAL_TOKEN
})
class OnlineClassTranscriptIntegrationTest extends AbstractIntegrationTest {

    static final String INTERNAL_TOKEN = "internal-worker-token-not-a-real-secret";

    private static final Instant START = Instant.now().minus(5, ChronoUnit.MINUTES);
    private static final Instant END = START.plus(1, ChronoUnit.HOURS);

    @Autowired
    private OnlineClassTranscriptService transcriptService;

    @Autowired
    private OnlineClassRepository classRepository;

    @Autowired
    private OnlineClassParticipantRepository participantRepository;

    @Autowired
    private OnlineClassTranscriptSegmentRepository segmentRepository;

    @Autowired
    private LessonPreparationRepository preparations;

    @Autowired
    private LessonPreparationService preparationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClassTranscriptionProvider transcriptionProvider;

    @MockitoBean
    private LiveClassMediaProvider mediaProvider;

    private User teacher;
    private User student;
    private AuthenticatedUser teacherPrincipal;
    private AuthenticatedUser studentPrincipal;
    private OnlineClass onlineClass;

    @BeforeEach
    void seed() {
        teacher = userRepository.save(User.invitedTeacher("Teacher", "teacher@test.local", "t1", END));
        student = userRepository.save(User.invitedStudent("Student", "student@test.local",
                teacher, "s1", END));
        teacherPrincipal = new AuthenticatedUser(teacher.getId(), teacher.getEmail(), teacher.getRole());
        studentPrincipal = new AuthenticatedUser(student.getId(), student.getEmail(), student.getRole());

        OnlineClass created = OnlineClass.forStudent(teacher, "event-1", "binding-1",
                "Maths", START, END, student);
        created.start(START);
        onlineClass = classRepository.save(created);

        when(transcriptionProvider.isConfigured()).thenReturn(true);
        when(transcriptionProvider.isTranscriptionRunning(anyString(), anyString())).thenReturn(true);
        when(transcriptionProvider.startTranscription(anyString(), anyString(), any(), any()))
                .thenReturn("dispatch-1");
        when(mediaProvider.isConfigured()).thenReturn(true);
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of(
                ParticipantIdentity.of(teacher.getId(), "teacher-tab"),
                ParticipantIdentity.of(student.getId(), "student-tab")));
    }

    private TranscriptSegmentRequest segment(String id, long startMs, long endMs, String text) {
        return new TranscriptSegmentRequest(id,
                ParticipantIdentity.of(student.getId(), "tab-1"),
                "Student", "de", startMs, endMs, text, 0.93);
    }

    // --- Teacher control -----------------------------------------------------

    @Test
    void theTeacherStartsAndStopsTranscription() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));

        assertThat(classRepository.findById(onlineClass.getId()).orElseThrow()
                .getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);

        transcriptService.stop(teacherPrincipal, onlineClass.getId());
        org.mockito.Mockito.verify(transcriptionProvider)
                .stopTranscription(anyString(), org.mockito.ArgumentMatchers.eq("dispatch-1"));
        assertThat(classRepository.findById(onlineClass.getId()).orElseThrow()
                .getTranscriptionState()).isEqualTo(ClassFeatureState.INACTIVE);
    }

    @Test
    void startingTwiceIsANoOp() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));

        org.mockito.Mockito.verify(transcriptionProvider, org.mockito.Mockito.times(1))
                .startTranscription(anyString(), anyString(), any(), any());
        verify(transcriptionProvider).isTranscriptionRunning(onlineClass.getRoomName(), "dispatch-1");
    }

    @Test
    void aNativeClosedDispatchIsReplacedOnTeacherReentryWithoutLosingTheTranscript() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        TranscriptSegmentResponse original = transcriptService.ingest(onlineClass.getId(),
                segment("before-native-close", 0, 1000, "Before native close"));
        when(transcriptionProvider.isTranscriptionRunning(onlineClass.getRoomName(), "dispatch-1"))
                .thenReturn(false);
        when(transcriptionProvider.startTranscription(anyString(), anyString(), any(), any()))
                .thenReturn("dispatch-after-native-close");

        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        transcriptService.ingest(onlineClass.getId(),
                segment("after-native-close", 5000, 6000, "After native close"));

        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OnlineClassStatus.LIVE);
        assertThat(reloaded.getActualStartAt()).isEqualTo(START);
        assertThat(reloaded.getActualEndAt()).isNull();
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);
        assertThat(reloaded.getTranscriptionDispatchId()).isEqualTo("dispatch-after-native-close");
        assertThat(classRepository.count()).isEqualTo(1);
        assertThat(transcriptService.segments(teacherPrincipal, onlineClass.getId()))
                .extracting(TranscriptSegmentResponse::text)
                .containsExactly("Before native close", "After native close");
        assertThat(segmentRepository.findByOnlineClassIdAndProviderSegmentId(
                onlineClass.getId(), "before-native-close").orElseThrow().getId()).isEqualTo(original.id());
        verify(transcriptionProvider, times(2)).startTranscription(
                org.mockito.ArgumentMatchers.eq(onlineClass.getRoomName()),
                org.mockito.ArgumentMatchers.eq(onlineClass.getId().toString()), any(),
                org.mockito.ArgumentMatchers.eq(START));
    }

    @Test
    void anUnknownDispatchStatusDoesNotClearOrDuplicateAnActiveRun() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        when(transcriptionProvider.isTranscriptionRunning(onlineClass.getRoomName(), "dispatch-1"))
                .thenThrow(new MediaProviderException("dispatch API unavailable"));

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de")))
                .isInstanceOf(ConflictException.class);

        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);
        assertThat(reloaded.getTranscriptionDispatchId()).isEqualTo("dispatch-1");
        verify(transcriptionProvider, times(1)).startTranscription(anyString(), anyString(), any(), any());
        verify(transcriptionProvider, never()).stopTranscription(anyString(), anyString());
    }

    @Test
    void anActiveFlagDoesNotBypassTeacherPresenceOnReentry() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de")))
                .isInstanceOf(ConflictException.class);

        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);
        assertThat(reloaded.getTranscriptionDispatchId()).isEqualTo("dispatch-1");
        verify(transcriptionProvider, times(1)).startTranscription(anyString(), anyString(), any(), any());
        verify(transcriptionProvider, never()).isTranscriptionRunning(anyString(), anyString());
    }

    @Test
    void anEmptyLiveKitRoomCannotStartTranscription() {
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), null))
                .isInstanceOf(ConflictException.class);

        assertNoDispatchAndLiveClassUnchanged();
    }

    @Test
    void aStudentOnlyRoomCannotStartTranscription() {
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of(
                ParticipantIdentity.of(student.getId(), "student-tab")));

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), null))
                .isInstanceOf(ConflictException.class);

        assertNoDispatchAndLiveClassUnchanged();
    }

    @Test
    void anAttendanceFlagFromTokenIssuanceDoesNotProveHostPresence() {
        // connect() marks this row joined when minting a token, before LiveKit
        // accepts the media connection. That optimistic flag must not start STT.
        OnlineClassParticipant host = OnlineClassParticipant.host(onlineClass, teacher);
        host.markJoined(START);
        participantRepository.saveAndFlush(host);
        assertThat(host.isConnected()).isTrue();
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), null))
                .isInstanceOf(ConflictException.class);

        assertNoDispatchAndLiveClassUnchanged();
    }

    @Test
    void aPresenceApiOutageCannotStartTranscriptionOrMarkItActive() {
        when(mediaProvider.listParticipantIdentities(anyString()))
                .thenThrow(new MediaProviderException("presence unavailable"));

        assertThatThrownBy(() -> transcriptService.start(teacherPrincipal, onlineClass.getId(), null))
                .isInstanceOf(ConflictException.class);

        assertNoDispatchAndLiveClassUnchanged();
    }

    @Test
    void aRejoiningTeacherContinuesTheSameDispatchAndExistingTranscript() {
        Instant originalStart = classRepository.findById(onlineClass.getId()).orElseThrow()
                .getActualStartAt();
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        TranscriptSegmentResponse original = transcriptService.ingest(onlineClass.getId(),
                segment("before-rejoin", 0, 1000, "Before reconnect"));

        // The first browser goes away. Rejoining with a different per-device
        // suffix still identifies the same owning teacher and the same lesson.
        when(mediaProvider.listParticipantIdentities(anyString())).thenReturn(List.of(
                ParticipantIdentity.of(teacher.getId(), "rejoined-tab"),
                ParticipantIdentity.of(student.getId(), "student-tab")));
        transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
        transcriptService.ingest(onlineClass.getId(),
                segment("after-rejoin", 1100, 2000, "After reconnect"));

        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);
        assertThat(reloaded.getTranscriptionDispatchId()).isEqualTo("dispatch-1");
        assertThat(reloaded.getActualStartAt()).isEqualTo(originalStart);
        assertThat(reloaded.getEventId()).isEqualTo("event-1");
        assertThat(classRepository.count()).isEqualTo(1);
        assertThat(transcriptService.segments(teacherPrincipal, onlineClass.getId()))
                .extracting(TranscriptSegmentResponse::text)
                .containsExactly("Before reconnect", "After reconnect");
        assertThat(segmentRepository.findByOnlineClassIdAndProviderSegmentId(
                onlineClass.getId(), "before-rejoin").orElseThrow().getId()).isEqualTo(original.id());
        verify(transcriptionProvider, times(1))
                .startTranscription(anyString(), anyString(), any(), any());
        verify(transcriptionProvider, never()).stopTranscription(anyString(), anyString());
    }

    @Test
    void twoConnectedTeacherTabsCreateOnlyOneDispatchAndPreserveEarlierSegments() throws Exception {
        TranscriptSegmentResponse earlier = transcriptService.ingest(onlineClass.getId(),
                segment("existing-segment", 0, 800, "Already saved"));
        CountDownLatch firstDispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch secondRequestStarted = new CountDownLatch(1);
        when(transcriptionProvider.startTranscription(anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    firstDispatchEntered.countDown();
                    if (!releaseDispatch.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release the first dispatch");
                    }
                    return "dispatch-1";
                });

        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transcriptService.start(
                    teacherPrincipal, onlineClass.getId(), List.of("de")));
            assertThat(firstDispatchEntered.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondRequestStarted.countDown();
                transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de"));
            });
            assertThat(secondRequestStarted.await(3, TimeUnit.SECONDS)).isTrue();
            releaseDispatch.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);

            verify(transcriptionProvider, times(1))
                    .startTranscription(anyString(), anyString(), any(), any());
            OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
            assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.ACTIVE);
            assertThat(reloaded.getTranscriptionDispatchId()).isEqualTo("dispatch-1");
            assertThat(transcriptService.segments(teacherPrincipal, onlineClass.getId()))
                    .extracting(TranscriptSegmentResponse::id).containsExactly(earlier.id());
            assertThat(classRepository.count()).isEqualTo(1);
        } finally {
            releaseDispatch.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertNoDispatchAndLiveClassUnchanged() {
        verify(transcriptionProvider, never()).startTranscription(anyString(), anyString(), any(), any());
        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.INACTIVE);
        assertThat(reloaded.getTranscriptionDispatchId()).isNull();
        assertThat(reloaded.getStatus()).isEqualTo(OnlineClassStatus.LIVE);
    }

    @Test
    void bothProductLanguagesAreHintedWhenNoneAreGiven() {
        transcriptService.start(teacherPrincipal, onlineClass.getId(), null);

        org.mockito.Mockito.verify(transcriptionProvider)
                .startTranscription(anyString(), anyString(),
                        org.mockito.ArgumentMatchers.eq(List.of("ru", "de")),
                        org.mockito.ArgumentMatchers.eq(START));
    }

    @Test
    void aStudentCannotControlOrReadTheTranscript() {
        assertThatThrownBy(() ->
                transcriptService.start(studentPrincipal, onlineClass.getId(), List.of("de")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> transcriptService.segments(studentPrincipal, onlineClass.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aProviderFailureDoesNotEndTheClass() {
        when(transcriptionProvider.startTranscription(anyString(), anyString(), any(), any()))
                .thenThrow(new MediaProviderException("agent unavailable"));

        assertThatThrownBy(() ->
                transcriptService.start(teacherPrincipal, onlineClass.getId(), List.of("de")))
                .isInstanceOf(ConflictException.class);

        OnlineClass reloaded = classRepository.findById(onlineClass.getId()).orElseThrow();
        // Transcription failed; the class is untouched and still live.
        assertThat(reloaded.getTranscriptionState()).isEqualTo(ClassFeatureState.FAILED);
        assertThat(reloaded.getStatus()).isEqualTo(OnlineClassStatus.LIVE);
    }

    // --- Worker ingestion ----------------------------------------------------

    @Test
    void aResentSegmentDoesNotDuplicate() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1000, "Guten Tag"));
        TranscriptSegmentResponse resent =
                transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1000, "Guten Tag"));

        assertThat(segmentRepository.count()).isEqualTo(1);
        assertThat(resent.text()).isEqualTo("Guten Tag");
    }

    @Test
    void theSpeakerIsResolvedFromTheIdentityNotFromWorkerInput() {
        TranscriptSegmentResponse stored =
                transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1000, "Hallo"));

        assertThat(stored.userId()).isEqualTo(student.getId());
    }

    @Test
    void anUnknownIdentityStillProducesALine() {
        TranscriptSegmentResponse stored = transcriptService.ingest(onlineClass.getId(),
                new TranscriptSegmentRequest("seg-x", "not-a-uuid", "Guest", "de",
                        0, 500, "anonymous speech", null));

        assertThat(stored.userId()).isNull();
        assertThat(stored.text()).isEqualTo("anonymous speech");
    }

    @Test
    void overlongSegmentTextIsTruncatedRatherThanRejected() {
        TranscriptSegmentResponse stored = transcriptService.ingest(onlineClass.getId(),
                segment("seg-long", 0, 1000, "a".repeat(9000)));

        assertThat(stored.text()).hasSize(OnlineClassTranscriptService.MAX_SEGMENT_TEXT_LENGTH);
    }

    @Test
    void everyFinalSegmentAppearsAutomaticallyInTheCorrectLessonTranscript() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-2", 2000, 3000, "zweite"));
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1500, "erste"));

        var prep = preparations.findByTeacherIdAndEventId(teacher.getId(), "event-1")
                .orElseThrow();
        assertThat(prep.hasTranscript()).isTrue();
        assertThat(prep.getSonioxTranscriptText())
                .contains("Student: erste", "Student: zweite");
        assertThat(prep.getSonioxTranscriptText().indexOf("erste"))
                .isLessThan(prep.getSonioxTranscriptText().indexOf("zweite"));
        assertThat(prep.getTranscriptTextForDisplay()).contains("erste", "zweite");

        transcriptService.ingest(onlineClass.getId(), segment("seg-2", 2000, 3000, "zweite"));
        assertThat(segmentRepository.count()).isEqualTo(2);
        assertThat(preparations.findByTeacherIdAndEventId(teacher.getId(), "event-1")
                .orElseThrow().getSonioxTranscriptText())
                .isEqualTo(prep.getSonioxTranscriptText());
    }

    @Test
    void aManualTranscriptCannotBeOverwrittenBySoniox() {
        var manual = com.mcschool.flashcard.lessons.LessonPreparation.create(teacher, "event-1");
        manual.updateNotes(null, null, null, "Notes written by teacher");
        preparations.saveAndFlush(manual);

        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 800, "Automatic speech"));
        var prep = preparations.findByTeacherIdAndEventId(teacher.getId(), "event-1")
                .orElseThrow();
        assertThat(prep.getTranscriptTextForDisplay()).isEqualTo("Notes written by teacher");
        assertThat(prep.getSonioxTranscriptText()).contains("Automatic speech");
    }

    @Test
    void savingLessonNotesDoesNotFreezeTheAutomaticTranscript() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 800, "First phrase"));
        var initial = preparationService.getOrCreate(teacherPrincipal, "event-1");
        assertThat(initial.transcriptText()).contains("First phrase");
        assertThat(initial.manualTranscriptText()).isNull();
        assertThat(initial.sonioxTranscriptText()).contains("First phrase");

        preparationService.update(teacherPrincipal, "event-1",
                new UpdateLessonPreparationRequest("Homework notes", null, null, ""));
        transcriptService.ingest(onlineClass.getId(), segment("seg-2", 900, 1800, "Second phrase"));

        var updated = preparationService.getOrCreate(teacherPrincipal, "event-1");
        assertThat(updated.homeworkNotes()).isEqualTo("Homework notes");
        assertThat(updated.manualTranscriptText()).isNull();
        assertThat(updated.transcriptText()).contains("First phrase", "Second phrase");
        assertThat(updated.sonioxTranscriptText()).contains("First phrase", "Second phrase");
    }

    @Test
    void anOldRoomCannotReplaceTheTranscriptOfACorrectedLesson() {
        transcriptService.ingest(onlineClass.getId(), segment("old-first", 0, 800, "Old pupil speech"));
        User replacement = userRepository.save(User.invitedStudent("Replacement", "replacement@test.local",
                teacher, "s2", END));
        OnlineClass retry = OnlineClass.forStudent(teacher, "event-1", "binding-2", "Corrected maths",
                START.plusSeconds(1), END.plusSeconds(1), replacement);
        retry.start(START.plusSeconds(1));
        retry = classRepository.saveAndFlush(retry);
        transcriptService.ingest(retry.getId(), new TranscriptSegmentRequest("new-first",
                ParticipantIdentity.of(replacement.getId(), "tab-1"), "Replacement", "de",
                0, 800, "New pupil speech", 0.93));

        transcriptService.ingest(onlineClass.getId(), segment("old-late", 900, 1800, "Late old speech"));
        var current = preparationService.getOrCreate(teacherPrincipal, "event-1");
        assertThat(current.sonioxTranscriptText()).contains("Replacement: New pupil speech")
                .doesNotContain("Old pupil speech", "Late old speech");
        assertThat(transcriptService.segments(teacherPrincipal, onlineClass.getId()))
                .extracting(TranscriptSegmentResponse::text)
                .containsExactly("Old pupil speech", "Late old speech");
    }

    // --- Internal token ------------------------------------------------------

    @Test
    void ingestionRequiresTheInternalToken() throws Exception {
        String body = """
                {"providerSegmentId":"seg-1","participantIdentity":"x","speakerLabel":"S",
                 "language":"de","startMs":0,"endMs":10,"text":"hi","confidence":0.9}
                """;
        String path = "/api/v1/internal/online-classes/" + onlineClass.getId() + "/transcript-segments";

        // No token at all.
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        // Wrong token.
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());

        // Correct token — note this is not an end-user JWT.
        mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void theInternalTokenDoesNotUnlockUserEndpoints() throws Exception {
        // The worker credential authorizes ingestion and nothing else.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/online-classes/upcoming")
                        .header("X-Internal-Token", INTERNAL_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    // --- Exports -------------------------------------------------------------

    @Test
    void segmentsComeBackInPlaybackOrder() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-2", 2000, 3000, "zweite"));
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1000, "erste"));

        assertThat(transcriptService.segments(teacherPrincipal, onlineClass.getId()))
                .extracting(TranscriptSegmentResponse::text)
                .containsExactly("erste", "zweite");
    }

    @Test
    void textExportIsSpeakerPrefixed() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1000, "erste"));

        assertThat(transcriptService.exportText(teacherPrincipal, onlineClass.getId()))
                .isEqualTo("Student: erste\n");
    }

    @Test
    void vttExportCarriesUsableTimings() {
        transcriptService.ingest(onlineClass.getId(), segment("seg-1", 0, 1500, "erste"));
        transcriptService.ingest(onlineClass.getId(), segment("seg-2", 2000, 3250, "zweite"));

        String vtt = transcriptService.exportVtt(teacherPrincipal, onlineClass.getId());

        assertThat(vtt).startsWith("WEBVTT");
        assertThat(vtt).contains("00:00:00.000 --> 00:00:01.500");
        assertThat(vtt).contains("00:00:02.000 --> 00:00:03.250");
        assertThat(vtt).contains("<v Student>erste");
    }

    @Test
    void vttTimestampsFormatHoursCorrectly() {
        assertThat(OnlineClassTranscriptService.formatTimestamp(3_723_456L))
                .isEqualTo("01:02:03.456");
        assertThat(OnlineClassTranscriptService.formatTimestamp(0L)).isEqualTo("00:00:00.000");
    }
}
