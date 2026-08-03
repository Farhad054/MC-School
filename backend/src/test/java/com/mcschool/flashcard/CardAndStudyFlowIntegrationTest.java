package com.mcschool.flashcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.mcschool.flashcard.cards.CardRepository;
import com.mcschool.flashcard.users.User;
import com.mcschool.flashcard.users.UserRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end coverage of the flashcard and study flow against real PostgreSQL:
 * a teacher creates and imports cards for their student, and the student runs a
 * study session that advances the SM-2 schedule. Also covers ownership and the
 * four-card minimum.
 */
class CardAndStudyFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@test.local";
    private static final String ADMIN_PASSWORD = "AdminSecret123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CardRepository cardRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String teacherToken;
    private String studentToken;
    private UUID studentId;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.save(User.bootstrapAdmin("Admin", ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));

        teacherToken = createActivatedTeacher("maria@test.local", "TeacherPass123!");
        String studentInvitation = postAndReturn("/api/v1/students", teacherToken,
                "{\"fullName\": \"Sam Student\", \"email\": \"sam@test.local\"}", status().isCreated());
        studentId = UUID.fromString(JsonPath.read(studentInvitation, "$.student.id"));
        studentToken = activate(JsonPath.read(studentInvitation, "$.invitationToken"), "StudentPass123!");
    }

    // --- Card management (teacher) ---

    @Test
    void teacherCreatesListsAndSummarisesCards() throws Exception {
        createCard(teacherToken, studentId, "2 + 2", "4");
        createCard(teacherToken, studentId, "3 + 3", "6");

        mockMvc.perform(get("/api/v1/students/{id}/cards", studentId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/students/{id}/cards/summary", studentId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.dueNow").value(2))
                .andExpect(jsonPath("$.learned").value(0));
    }

    @Test
    void teacherEditsAndDeletesOwnCard() throws Exception {
        String created = createCardRaw(teacherToken, studentId, "old q", "old a");
        String cardId = JsonPath.read(created, "$.id");

        mockMvc.perform(put("/api/v1/cards/{id}", cardId)
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"new q\", \"correctAnswer\": \"new a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("new q"));

        mockMvc.perform(delete("/api/v1/cards/{id}", cardId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void importPreviewParsesTextAndReportsBadLines() throws Exception {
        mockMvc.perform(post("/api/v1/cards/import/preview")
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rawText": "2+2 -> 4\\n3+3 -> 6\\nbad line", "questionAnswerSeparator": "->", "cardSeparator": "\\n"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.cards[0].question").value("2+2"))
                .andExpect(jsonPath("$.warnings.length()").value(1));
    }

    @Test
    void confirmedImportCreatesCards() throws Exception {
        mockMvc.perform(post("/api/v1/students/{id}/cards/import", studentId)
                        .header("Authorization", "Bearer " + teacherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cards": [
                                    {"question": "2+2", "correctAnswer": "4"},
                                    {"question": "3+3", "correctAnswer": "6"}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/v1/students/{id}/cards", studentId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void teacherCannotTouchAnotherTeachersStudentOrCards() throws Exception {
        String otherTeacher = createActivatedTeacher("other@test.local", "OtherPass123!");
        String created = createCardRaw(teacherToken, studentId, "q", "a");
        String cardId = JsonPath.read(created, "$.id");

        // Another teacher cannot see the student (reported as 404, not 403).
        mockMvc.perform(get("/api/v1/students/{id}/cards", studentId)
                        .header("Authorization", "Bearer " + otherTeacher))
                .andExpect(status().isNotFound());

        // ...nor edit their card.
        mockMvc.perform(put("/api/v1/cards/{id}", cardId)
                        .header("Authorization", "Bearer " + otherTeacher)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"hacked\", \"correctAnswer\": \"x\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingACardReferencedByASessionArchivesItSafely() throws Exception {
        Map<UUID, String> answers = createFourCards();
        // Start a session so study_session_items reference the cards.
        postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"SCHEDULED\"}", status().isCreated());
        UUID cardToDelete = answers.keySet().iterator().next();

        // Deleting a card the session references must archive it, not fail with a FK error.
        mockMvc.perform(delete("/api/v1/cards/{id}", cardToDelete)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isNoContent());

        // The archived card disappears from the teacher's list (3 of 4 remain).
        mockMvc.perform(get("/api/v1/students/{id}/cards", studentId)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        // Acting on an already-archived card is treated as not found.
        mockMvc.perform(delete("/api/v1/cards/{id}", cardToDelete)
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isNotFound());
    }

    // --- Study flow (student) ---

    @Test
    void studentCannotStartSessionWithFewerThanFourCards() throws Exception {
        createCard(teacherToken, studentId, "1", "one");
        createCard(teacherToken, studentId, "2", "two");
        createCard(teacherToken, studentId, "3", "three");

        mockMvc.perform(post("/api/v1/study/sessions")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"SCHEDULED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_ENOUGH_CARDS"));
    }

    @Test
    void studentCompletesScheduledSessionWhichAdvancesTheSchedule() throws Exception {
        Map<UUID, String> answers = createFourCards();

        // Today: 4 due cards, session available.
        mockMvc.perform(get("/api/v1/study/today").header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueCardCount").value(4))
                .andExpect(jsonPath("$.canStartScheduled").value(true));

        String start = postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"SCHEDULED\"}",
                status().isCreated());
        UUID sessionId = UUID.fromString(JsonPath.read(start, "$.id"));

        playSessionAnsweringCorrectly(sessionId, answers);

        // Result screen: all four correct on the first try, next review scheduled.
        mockMvc.perform(get("/api/v1/study/sessions/{id}/result", sessionId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCards").value(4))
                .andExpect(jsonPath("$.correctFirstTry").value(4))
                .andExpect(jsonPath("$.nextReviewDate").value(LocalDate.now().plusDays(3).toString()));

        // Every card advanced to repetition 1, due in 3 days.
        cardRepository.findAllByStudentIdAndArchivedFalse(studentId)
                .forEach(card -> assertThat(card.getRepetitionNumber()).isEqualTo(1));

        // Nothing due now, so no scheduled session can start until the due date.
        mockMvc.perform(get("/api/v1/study/today").header("Authorization", "Bearer " + studentToken))
                .andExpect(jsonPath("$.dueCardCount").value(0))
                .andExpect(jsonPath("$.canStartScheduled").value(false));
    }

    @Test
    void wrongAnswerReturnsTheCardLaterInTheSameSession() throws Exception {
        Map<UUID, String> answers = createFourCards();
        String start = postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"SCHEDULED\"}",
                status().isCreated());
        UUID sessionId = UUID.fromString(JsonPath.read(start, "$.id"));

        // Answer the first card wrong.
        String question = mockMvc.perform(get("/api/v1/study/sessions/{id}/current-question", sessionId)
                        .header("Authorization", "Bearer " + studentToken))
                .andReturn().getResponse().getContentAsString();
        UUID firstCardId = UUID.fromString(JsonPath.read(question, "$.cardId"));

        mockMvc.perform(post("/api/v1/study/sessions/{id}/answer", sessionId)
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardId\": \"" + firstCardId + "\", \"selectedAnswer\": \"definitely wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(false))
                .andExpect(jsonPath("$.correctAnswer").value(answers.get(firstCardId)))
                .andExpect(jsonPath("$.sessionCompleted").value(false));

        // Finish the session answering everything correctly.
        playSessionAnsweringCorrectly(sessionId, answers);

        // The card that was missed does not count as first-try correct: 3 of 4.
        mockMvc.perform(get("/api/v1/study/sessions/{id}/result", sessionId)
                        .header("Authorization", "Bearer " + studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctFirstTry").value(3));
    }

    @Test
    void practiceSessionDoesNotChangeTheSchedule() throws Exception {
        Map<UUID, String> answers = createFourCards();
        String start = postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"PRACTICE\"}",
                status().isCreated());
        UUID sessionId = UUID.fromString(JsonPath.read(start, "$.id"));

        playSessionAnsweringCorrectly(sessionId, answers);

        // Practice leaves every card at repetition 0, still due today.
        cardRepository.findAllByStudentIdAndArchivedFalse(studentId).forEach(card -> {
            assertThat(card.getRepetitionNumber()).isZero();
            assertThat(card.getDueDate()).isEqualTo(LocalDate.now());
        });
    }

    @Test
    void onlyOneSessionCanBeInProgress() throws Exception {
        createFourCards();
        postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"SCHEDULED\"}", status().isCreated());

        mockMvc.perform(post("/api/v1/study/sessions")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"PRACTICE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SESSION_IN_PROGRESS"));
    }

    @Test
    void studentCannotAccessAnotherStudentsSession() throws Exception {
        createFourCards();
        String start = postAndReturn("/api/v1/study/sessions", studentToken, "{\"type\": \"SCHEDULED\"}",
                status().isCreated());
        UUID sessionId = UUID.fromString(JsonPath.read(start, "$.id"));

        String otherStudentInvitation = postAndReturn("/api/v1/students", teacherToken,
                "{\"fullName\": \"Other Student\", \"email\": \"other-student@test.local\"}",
                status().isCreated());
        String otherStudentToken = activate(JsonPath.read(otherStudentInvitation, "$.invitationToken"),
                "OtherStudent123!");

        mockMvc.perform(get("/api/v1/study/sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + otherStudentToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherCannotAccessStudentStudyEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/study/today").header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isForbidden());
    }

    // --- Settings ---

    @Test
    void studentCanChangeInterfaceLanguage() throws Exception {
        mockMvc.perform(put("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"preferredLanguage\": \"DE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredLanguage").value("DE"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + studentToken))
                .andExpect(jsonPath("$.preferredLanguage").value("DE"));
    }

    // --- Helpers ---

    /** Creates four cards for the student and returns a card-id → correct-answer map. */
    private Map<UUID, String> createFourCards() throws Exception {
        Map<UUID, String> answers = new HashMap<>();
        answers.putAll(createCard(teacherToken, studentId, "2 + 2", "4"));
        answers.putAll(createCard(teacherToken, studentId, "3 + 3", "6"));
        answers.putAll(createCard(teacherToken, studentId, "4 + 4", "8"));
        answers.putAll(createCard(teacherToken, studentId, "5 + 5", "10"));
        return answers;
    }

    private Map<UUID, String> createCard(String token, UUID student, String question, String answer)
            throws Exception {
        String body = createCardRaw(token, student, question, answer);
        return Map.of(UUID.fromString(JsonPath.read(body, "$.id")), answer);
    }

    private String createCardRaw(String token, UUID student, String question, String answer) throws Exception {
        return mockMvc.perform(post("/api/v1/students/{id}/cards", student)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"" + question + "\", \"correctAnswer\": \"" + answer + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    /** Plays a session to completion, always choosing the correct answer for each card shown. */
    private void playSessionAnsweringCorrectly(UUID sessionId, Map<UUID, String> answers) throws Exception {
        for (int guard = 0; guard < 100; guard++) {
            MvcResult questionResult = mockMvc.perform(
                            get("/api/v1/study/sessions/{id}/current-question", sessionId)
                                    .header("Authorization", "Bearer " + studentToken))
                    .andReturn();
            if (questionResult.getResponse().getStatus() != 200) {
                return; // no more pending cards
            }
            String question = questionResult.getResponse().getContentAsString();
            UUID cardId = UUID.fromString(JsonPath.read(question, "$.cardId"));

            String answerResult = mockMvc.perform(post("/api/v1/study/sessions/{id}/answer", sessionId)
                            .header("Authorization", "Bearer " + studentToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"cardId\": \"" + cardId + "\", \"selectedAnswer\": \""
                                    + answers.get(cardId) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.correct").value(true))
                    .andReturn().getResponse().getContentAsString();
            if (Boolean.TRUE.equals(JsonPath.read(answerResult, "$.sessionCompleted"))) {
                return;
            }
        }
        throw new AssertionError("Session did not complete within the expected number of answers");
    }

    private String createActivatedTeacher(String email, String password) throws Exception {
        String adminToken = login(ADMIN_EMAIL, ADMIN_PASSWORD);
        String invitation = postAndReturn("/api/v1/teachers", adminToken,
                "{\"fullName\": \"Teacher\", \"email\": \"" + email + "\"}", status().isCreated());
        return activate(JsonPath.read(invitation, "$.invitationToken"), password);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private String activate(String invitationToken, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"invitationToken\": \"" + invitationToken + "\", \"password\": \""
                                + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    /** POST helper returning the response body, asserting the given status. */
    private String postAndReturn(String url, String token, String body,
                                 org.springframework.test.web.servlet.ResultMatcher expectedStatus) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(expectedStatus)
                .andReturn().getResponse().getContentAsString();
    }
}
