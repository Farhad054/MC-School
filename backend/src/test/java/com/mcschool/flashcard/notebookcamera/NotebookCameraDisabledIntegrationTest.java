package com.mcschool.flashcard.notebookcamera;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcschool.flashcard.AbstractIntegrationTest;
import com.mcschool.flashcard.auth.JwtService;
import com.mcschool.flashcard.lessons.LessonHistoryEntry;
import com.mcschool.flashcard.lessons.LessonHistoryRepository;
import com.mcschool.flashcard.lessons.dto.GroupLessonResponse;
import com.mcschool.flashcard.liveclasses.provider.LiveClassMediaProvider;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Disabling the master switch must also revoke access through existing public QR/session secrets. */
@TestPropertySource(properties = {"app.online-classes.enabled=false", "app.online-classes.pilot-owner-id="})
class NotebookCameraDisabledIntegrationTest extends AbstractIntegrationTest {
    private static final String EVENT = "disabled-camera-lesson";
    private static final String PAIRING_SECRET = "a".repeat(43);
    private static final String SESSION_SECRET = "b".repeat(43);
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private LessonHistoryRepository lessons;
    @Autowired private NotebookCameraRepository cameras;
    @MockitoBean private LiveClassMediaProvider media;
    private User teacher;
    private User pupil;
    private NotebookCamera camera;

    @BeforeEach
    void seedOtherwiseValidCamera() {
        teacher = users.save(User.activeTeacher("Teacher", "disabled@test.local", "disabledteacher", "hash"));
        pupil = users.save(User.managedStudent("Pupil", teacher));
        Instant now = Instant.now();
        lessons.save(LessonHistoryEntry.create(teacher, new GroupLessonResponse(EVENT, EVENT, null, null,
                pupil.getId(), pupil.getFullName(), List.of(pupil.getId()), "Lesson", now, now.plusSeconds(3600), null, null)));
        camera = cameras.save(NotebookCamera.create(teacher.getId(), EVENT, pupil.getId(), null,
                NotebookCameraService.hash(PAIRING_SECRET), now, now.plusSeconds(300), now.plusSeconds(3600)));
        when(media.isConfigured()).thenReturn(true);
    }

    @Test
    void activeTeacherAndPupilCannotReachDisabledAccountRoutes() throws Exception {
        for (User caller : List.of(teacher, pupil)) {
            mvc.perform(get("/api/v1/notebook-cameras/lessons/{event}", EVENT)
                            .header("Authorization", "Bearer " + jwt.generateToken(caller)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/lesson-board/{event}", EVENT)
                            .header("Authorization", "Bearer " + jwt.generateToken(caller)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void correctExistingPublicSecretsCannotBypassDisabledClassroom() throws Exception {
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/claim", camera.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + PAIRING_SECRET + "\"}"))
                .andExpect(status().isNotFound());
        camera.claim(NotebookCameraService.hash(SESSION_SECRET), Instant.now());
        cameras.save(camera);
        mvc.perform(post("/api/v1/public/notebook-cameras/{id}/connection", camera.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionToken\":\"" + SESSION_SECRET + "\"}"))
                .andExpect(status().isNotFound());
    }
}
