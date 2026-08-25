package com.mcschool.flashcard.homeworks;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.homeworks.dto.CreateHomeworkRequest;
import com.mcschool.flashcard.homeworks.dto.HomeworkResponse;
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

@RestController
@RequestMapping("/api/v1")
public class HomeworkController {

    private final HomeworkService homeworkService;

    public HomeworkController(HomeworkService homeworkService) {
        this.homeworkService = homeworkService;
    }

    @PostMapping("/students/{studentId}/homeworks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER')")
    public HomeworkResponse createHomework(@AuthenticationPrincipal AuthenticatedUser caller,
                                           @PathVariable UUID studentId,
                                           @Valid @RequestBody CreateHomeworkRequest request) {
        return homeworkService.createHomework(caller, studentId, request);
    }

    @GetMapping("/students/{studentId}/homeworks")
    @PreAuthorize("hasRole('TEACHER')")
    public List<HomeworkResponse> listForTeacher(@AuthenticationPrincipal AuthenticatedUser caller,
                                                 @PathVariable UUID studentId) {
        return homeworkService.listForTeacher(caller, studentId);
    }

    @GetMapping("/study/homeworks")
    @PreAuthorize("hasRole('STUDENT')")
    public List<HomeworkResponse> listForStudent(@AuthenticationPrincipal AuthenticatedUser caller) {
        return homeworkService.listForStudent(caller);
    }
}
