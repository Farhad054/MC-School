package com.mcschool.flashcard.notebookcamera;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mcschool.flashcard.AbstractIntegrationTest;
import com.mcschool.flashcard.auth.JwtService;
import com.mcschool.flashcard.lessons.LessonHistoryEntry;
import com.mcschool.flashcard.lessons.LessonHistoryRepository;
import com.mcschool.flashcard.lessons.dto.GroupLessonResponse;
import com.mcschool.flashcard.liveclasses.OnlineClass;
import com.mcschool.flashcard.liveclasses.OnlineClassParticipant;
import com.mcschool.flashcard.liveclasses.OnlineClassParticipantRepository;
import com.mcschool.flashcard.liveclasses.OnlineClassRepository;
import com.mcschool.flashcard.liveclasses.provider.LiveClassMediaProvider;
import com.mcschool.flashcard.liveclasses.provider.ParticipantConnection;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real JWT/HTTP/PostgreSQL authorization after enabling the classroom for every teacher. */
@TestPropertySource(properties = {"app.online-classes.enabled=true", "app.online-classes.pilot-owner-id="})
class NotebookCameraTeacherIsolationIntegrationTest extends AbstractIntegrationTest {
    private static final String EVENT_A = "teacher-a-lesson";
    private static final String EVENT_B = "teacher-b-lesson";
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private LessonHistoryRepository lessons;
    @Autowired private OnlineClassRepository classes;
    @Autowired private OnlineClassParticipantRepository participants;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private LiveClassMediaProvider media;

    private User teacherA;
    private User teacherB;
    private User pupilA;
    private User pupilB;
    private OnlineClass classA;
    private OnlineClass classB;

    private record Pairing(UUID id, String token) {}

    @BeforeEach
    void seedIndependentClassrooms() {
        teacherA = users.save(User.activeTeacher("Teacher A", "a@test.local", "teacher-a", "hash"));
        teacherB = users.save(User.activeTeacher("Teacher B", "b@test.local", "teacher-b", "hash"));
        pupilA = users.save(User.managedStudent("Pupil A", teacherA));
        pupilB = users.save(User.managedStudent("Pupil B", teacherB));
        classA = createLesson(teacherA, pupilA, EVENT_A);
        classB = createLesson(teacherB, pupilB, EVENT_B);
        when(media.isConfigured()).thenReturn(true);
        when(media.createConnection(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> new ParticipantConnection("wss://test.invalid", "test-media-token",
                        call.getArgument(1), call.getArgument(0), Instant.now().plus(call.<Duration>getArgument(4))));
    }

    private OnlineClass createLesson(User teacher, User pupil, String event) {
        Instant now = Instant.now();
        lessons.save(LessonHistoryEntry.create(teacher, new GroupLessonResponse(event, event, null, null,
                pupil.getId(), pupil.getFullName(), List.of(pupil.getId()), event,
                now.minusSeconds(60), now.plusSeconds(3600), null, null)));
        OnlineClass room = OnlineClass.forStudent(teacher, event, event, event,
                now.minusSeconds(60), now.plusSeconds(3600), pupil);
        room.start(now);
        room = classes.save(room);
        OnlineClassParticipant member = OnlineClassParticipant.student(room, pupil);
        member.admit();
        participants.save(member);
        return room;
    }

    private String auth(User user) { return "Bearer " + jwt.generateToken(user); }

    private Pairing pair(User caller, User pupil, String event, OnlineClass room) throws Exception {
        String result = mvc.perform(post("/api/v1/notebook-cameras/lessons/{event}", event)
                        .header("Authorization", auth(caller)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + pupil.getId() + "\",\"classId\":\"" + room.getId() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new Pairing(UUID.fromString(JsonPath.read(result, "$.camera.cameraId")),
                JsonPath.read(result, "$.pairingToken"));
    }

    private String claim(Pairing pairing) throws Exception {
        String result = mvc.perform(post("/api/v1/public/notebook-cameras/{id}/claim", pairing.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + pairing.token() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(result, "$.sessionToken");
    }

    @Test
    void bothTeachersAndTheirOwnPupilsCanUseTheirIndependentCameras() throws Exception {
        Pairing first = pair(teacherA, pupilA, EVENT_A, classA);
        Pairing second = pair(pupilB, pupilB, EVENT_B, classB);
        for (User caller : List.of(teacherA, pupilA)) {
            mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(caller)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].cameraId").value(first.id().toString()));
            mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", first.id()).header("Authorization", auth(caller)))
                    .andExpect(status().isOk());
        }
        for (User caller : List.of(teacherB, pupilB)) {
            mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_B).header("Authorization", auth(caller)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].cameraId").value(second.id().toString()));
            mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", second.id()).header("Authorization", auth(caller)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void anotherTeacherOrPupilCannotListConnectRevokeOrCreateForTheFirstLesson() throws Exception {
        Pairing first = pair(pupilA, pupilA, EVENT_A, classA);
        for (User outsider : List.of(teacherB, pupilB)) {
            mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(outsider)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", first.id()).header("Authorization", auth(outsider)))
                    .andExpect(status().isNotFound());
            mvc.perform(delete("/api/v1/notebook-cameras/{id}", first.id()).header("Authorization", auth(outsider)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/notebook-cameras/lessons/{event}", EVENT_A)
                            .header("Authorization", auth(outsider)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"studentId\":\"" + pupilA.getId() + "\",\"classId\":\"" + classA.getId() + "\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/online-classes/{id}", classA.getId()).header("Authorization", auth(outsider)))
                    .andExpect(status().isNotFound());
        }
        // Guessing another classroom ID cannot attach its camera to the authorized lesson.
        mvc.perform(post("/api/v1/notebook-cameras/lessons/{event}", EVENT_A)
                        .header("Authorization", auth(teacherA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentId\":\"" + pupilA.getId() + "\",\"classId\":\"" + classB.getId() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicPairingAndPhoneSecretsCannotBeReusedForAnotherCamera() throws Exception {
        Pairing first = pair(pupilA, pupilA, EVENT_A, classA);
        Pairing second = pair(pupilB, pupilB, EVENT_B, classB);
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/claim", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + second.token() + "\"}"))
                .andExpect(status().isNotFound());
        String firstSession = claim(first);
        String secondSession = claim(second);
        assertThat(firstSession).isNotEqualTo(secondSession);
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", second.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + firstSession + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + firstSession + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/claim", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + first.token() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void revocationInvalidatesViewerAndPhoneAccessWithoutAffectingOtherTeachers() throws Exception {
        Pairing first = pair(pupilA, pupilA, EVENT_A, classA);
        Pairing second = pair(pupilB, pupilB, EVENT_B, classB);
        String session = claim(first);
        mvc.perform(delete("/api/v1/notebook-cameras/{id}", first.id()).header("Authorization", auth(teacherA)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", first.id()).header("Authorization", auth(pupilA)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + session + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(teacherA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", second.id()).header("Authorization", auth(teacherB)))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveOrArchivedPupilsLoseExistingPhoneAndAccountAccess(boolean archived) throws Exception {
        Pairing first = pair(pupilA, pupilA, EVENT_A, classA);
        String session = claim(first);
        if (archived) pupilA.archive(); else pupilA.deactivateManagedStudent();
        users.save(pupilA);
        mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(pupilA)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notebook-cameras/{id}/connection", first.id()).header("Authorization", auth(teacherA)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + session + "\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inactiveOrArchivedTeachersInvalidateTheirPupilsExistingPhoneSessions(boolean archived) throws Exception {
        Pairing first = pair(pupilA, pupilA, EVENT_A, classA);
        String session = claim(first);
        if (archived) {
            teacherA.archive();
            users.save(teacherA);
        } else jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", teacherA.getId());
        mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(pupilA)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", first.id())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + session + "\"}"))
                .andExpect(status().isNotFound());
        pair(pupilB, pupilB, EVENT_B, classB);
        mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_B).header("Authorization", auth(teacherB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anActivePupilWithoutAnAssignedTeacherCannotReachNotebookOrStandaloneBoards() throws Exception {
        User unassigned = users.save(User.managedStudent("Unassigned pupil", null));
        mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT_A).header("Authorization", auth(unassigned)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/lesson-board/{event}", EVENT_A).header("Authorization", auth(unassigned)))
                .andExpect(status().isForbidden());
    }

    @Test
    void standaloneLessonBoardsRemainSeparatedByTeacherAndEnrollment() throws Exception {
        mvc.perform(post("/api/v1/lesson-board/{event}/start", EVENT_A).header("Authorization", auth(teacherA)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/lesson-board/{event}/start", EVENT_B).header("Authorization", auth(teacherB)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/lesson-board/{event}", EVENT_B).header("Authorization", auth(pupilB)))
                .andExpect(status().isOk());
        for (User outsider : List.of(teacherB, pupilB)) {
            mvc.perform(get("/api/v1/lesson-board/{event}", EVENT_A).header("Authorization", auth(outsider)))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/lesson-board/{event}/actions", EVENT_A).header("Authorization", auth(outsider))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"CLEAR\"}"))
                    .andExpect(status().isNotFound());
        }
    }
}
