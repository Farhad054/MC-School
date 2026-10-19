package com.mcschool.flashcard.lessons;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OwnerWorkbookPdfRenderServiceTest {
    private static Map<String,Object> document(int minutes) {
        return Map.of("meta", Map.of("school", "MINDCRAFTI SCHOOL", "durationMinutes", minutes),
                "workbookPages", List.of(Map.of("blocks", List.of(Map.of("type", "exercise",
                        "rows", List.of(Map.of("label", "a", "math", List.of("1/2", "=", "_____"))))))),
                "teacherPages", List.of(Map.of("blocks", List.of(Map.of("type", "solutions",
                        "items", List.of(Map.of("label", "a", "math", List.of("1/2", "=", "1/2"))))))));
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
}
