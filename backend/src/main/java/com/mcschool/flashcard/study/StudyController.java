package com.mcschool.flashcard.study;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.cards.dto.CardResponse;
import com.mcschool.flashcard.study.dto.AnswerRequest;
import com.mcschool.flashcard.study.dto.AnswerResultResponse;
import com.mcschool.flashcard.study.dto.QuestionResponse;
import com.mcschool.flashcard.study.dto.SessionResponse;
import com.mcschool.flashcard.study.dto.SessionResultResponse;
import com.mcschool.flashcard.study.dto.StartSessionRequest;
import com.mcschool.flashcard.study.dto.TodayResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Student-only study flow. All routes act on the calling student's own data. */
@RestController
@RequestMapping("/api/v1/study")
@PreAuthorize("hasRole('STUDENT')")
public class StudyController {

    private final StudyService studyService;

    public StudyController(StudyService studyService) {
        this.studyService = studyService;
    }

    @GetMapping("/today")
    public TodayResponse today(@AuthenticationPrincipal AuthenticatedUser caller) {
        return studyService.today(caller);
    }

    @GetMapping("/cards")
    public List<CardResponse> myCards(@AuthenticationPrincipal AuthenticatedUser caller) {
        return studyService.listMyCards(caller);
    }

    @GetMapping("/homeworks/{homeworkId}/cards")
    public List<CardResponse> homeworkCards(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @PathVariable UUID homeworkId) {
        return studyService.listHomeworkCards(caller, homeworkId);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse startSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @Valid @RequestBody StartSessionRequest request) {
        return studyService.startSession(caller, request);
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionResponse getSession(@AuthenticationPrincipal AuthenticatedUser caller,
                                      @PathVariable UUID sessionId) {
        return studyService.getSession(caller, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/current-question")
    public QuestionResponse currentQuestion(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @PathVariable UUID sessionId) {
        return studyService.currentQuestion(caller, sessionId);
    }

    @PostMapping("/sessions/{sessionId}/answer")
    public AnswerResultResponse answer(@AuthenticationPrincipal AuthenticatedUser caller,
                                       @PathVariable UUID sessionId,
                                       @Valid @RequestBody AnswerRequest request) {
        return studyService.answer(caller, sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/result")
    public SessionResultResponse result(@AuthenticationPrincipal AuthenticatedUser caller,
                                        @PathVariable UUID sessionId) {
        return studyService.result(caller, sessionId);
    }
}
