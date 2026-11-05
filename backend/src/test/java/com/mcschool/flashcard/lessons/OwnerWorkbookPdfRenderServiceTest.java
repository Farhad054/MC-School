package com.mcschool.flashcard.lessons;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.settings.SchoolBrandGuideService;
import com.mcschool.flashcard.users.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class OwnerWorkbookPdfRenderServiceTest {
    private static Map<String,Object> document(int minutes) {
        return Map.of("meta", Map.of("school", "MINDCRAFTI SCHOOL", "durationMinutes", minutes),
                "workbookPages", List.of(Map.of("blocks", List.of(Map.of("type", "exercise",
                        "rows", List.of(Map.of("label", "a", "math", List.of("1/2", "=", "_____"))))))),
                "teacherPages", List.of(Map.of("blocks", List.of(Map.of("type", "solutions",
                        "items", List.of(Map.of("label", "a", "math", List.of("1/2", "=", "1/2"))))))));
    }

    @Test
    void thirdConcurrentRenderIsRejectedBeforeBrandIoOrDraftCreationAndSlotsAreReleased() throws Exception {
        ObjectMapper json = new ObjectMapper();
        LessonPdfDraftService drafts = mock(LessonPdfDraftService.class);
        SchoolBrandGuideService brand = mock(SchoolBrandGuideService.class);
        var service = new OwnerWorkbookPdfRenderService(json, drafts, brand);
        var teacher = new AuthenticatedUser(UUID.randomUUID(), "teacher@test.local", Role.TEACHER);
        String lesson = json.writeValueAsString(document(60));
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        when(brand.pdfForStaff(teacher)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test release");
            throw new IllegalStateException("Controlled brand read failure");
        });
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.create(teacher, "lesson-one", lesson, "", "")));
            var second = workers.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.create(teacher, "lesson-two", lesson, "", "")));
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Both render slots should be occupied");
            ResponseStatusException busy = assertThrows(ResponseStatusException.class,
                    () -> service.create(teacher, "lesson-three", lesson, "", ""));
            assertEquals(503, busy.getStatusCode().value());
            assertTrue(busy.getReason().contains("same idempotencyKey"));
            verify(brand, times(2)).pdfForStaff(teacher);
            verifyNoInteractions(drafts);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class,
                    () -> service.create(teacher, "lesson-after-release", lesson, "", ""));
            verify(brand, times(3)).pdfForStaff(teacher);
            verifyNoInteractions(drafts);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void validationFailuresReleaseRenderPermitsWithoutSideEffects() {
        LessonPdfDraftService drafts = mock(LessonPdfDraftService.class);
        SchoolBrandGuideService brand = mock(SchoolBrandGuideService.class);
        var service = new OwnerWorkbookPdfRenderService(new ObjectMapper(), drafts, brand);
        var teacher = new AuthenticatedUser(UUID.randomUUID(), "teacher@test.local", Role.TEACHER);
        for (int request = 0; request < 4; request++) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.create(teacher, "lesson", "{}", "", ""));
        }
        verifyNoInteractions(brand, drafts);
    }

    @Test
    void acceptsActualCalendarDurations() {
        assertEquals(1, OwnerWorkbookPdfRenderService.validateDocument(document(60)));
        assertEquals(1, OwnerWorkbookPdfRenderService.validateDocument(document(90)));
    }

    @Test
    void forbidsHomeworkAndInvalidLengths() {
        var lesson = new java.util.LinkedHashMap<String,Object>(document(90));
        lesson.put("homework", "assign immediately");
        assertThrows(IllegalArgumentException.class,
                () -> OwnerWorkbookPdfRenderService.validateDocument(lesson));
        assertThrows(IllegalArgumentException.class,
                () -> OwnerWorkbookPdfRenderService.validateDocument(document(10)));
    }

    @Test
    void blocksTeacherSolutionsInStudentFile() {
        var lesson = new java.util.LinkedHashMap<String,Object>(document(90));
        lesson.put("workbookPages", List.of(Map.of("blocks", List.of(Map.of("type", "solutions")))));
        assertThrows(IllegalArgumentException.class,
                () -> OwnerWorkbookPdfRenderService.validateDocument(lesson));
    }

    @Test
    void nestedLayoutsCountExercisesAndRejectAnswerLeaks() {
        var lesson = new java.util.LinkedHashMap<String,Object>(document(60));
        lesson.put("workbookPages", List.of(Map.of("blocks",List.of(Map.of("type","split",
                "left",List.of(Map.of("type","taskCard","title","Solve")),
                "right",List.of(Map.of("type","coordinateGrid")))))));
        assertEquals(1,OwnerWorkbookPdfRenderService.validateDocument(lesson));
        lesson.put("workbookPages", List.of(Map.of("blocks",List.of(Map.of("type","grid",
                "children",List.of(Map.of("type","taskCard","children",List.of(Map.of("type","solutions")))))))));
        assertThrows(IllegalArgumentException.class,()->OwnerWorkbookPdfRenderService.validateDocument(lesson));
    }
}
