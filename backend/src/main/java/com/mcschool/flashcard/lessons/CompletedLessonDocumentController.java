package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Completed lesson PDFs, one shared board and one personal board per student. */
@RestController
@RequestMapping("/api/v1/lesson-preparations/{eventId}/completed-documents")
@PreAuthorize("hasRole('TEACHER')")
public class CompletedLessonDocumentController {
    private final JdbcTemplate jdbc;
    private final LessonPreparationService lessons;

    public CompletedLessonDocumentController(JdbcTemplate jdbc, LessonPreparationService lessons) {
        this.jdbc = jdbc;
        this.lessons = lessons;
    }

    public record DocumentInfo(UUID id, UUID classId, String boardId, String filename, String savedAt) {}

    @GetMapping
    public List<DocumentInfo> list(@AuthenticationPrincipal AuthenticatedUser teacher, @PathVariable String eventId) {
        lessons.require(teacher, eventId);
        return jdbc.query("""
            SELECT id, class_id, board_id, filename, updated_at
            FROM completed_lesson_documents
            WHERE teacher_id = ? AND event_id = ?
            ORDER BY updated_at DESC
            """, (rs, n) -> new DocumentInfo(rs.getObject("id", UUID.class),
                rs.getObject("class_id", UUID.class), rs.getString("board_id"),
                rs.getString("filename"), rs.getTimestamp("updated_at").toInstant().toString()),
            teacher.id(), eventId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentInfo save(@AuthenticationPrincipal AuthenticatedUser teacher,
                             @PathVariable String eventId,
                             @RequestParam UUID classId,
                             @RequestParam String boardId,
                             @RequestParam("file") MultipartFile file) throws Exception {
        lessons.require(teacher, eventId);
        if (!boardId.equals("board-1") && !boardId.matches("personal-[0-9a-fA-F-]{36}"))
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid board");
        if (file.getSize() < 5 || file.getSize() > 25 * 1024 * 1024)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "PDF size out of range");
        byte[] bytes = file.getBytes();
        if (bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Expected PDF");
        // Prevent saving another teacher's classroom or a foreign event.
        Integer owned = jdbc.queryForObject("""
            SELECT count(*) FROM online_classes WHERE id = ? AND teacher_id = ? AND calendar_event_id = ?
            """, Integer.class, classId, teacher.id(), eventId);
        if (owned == null || owned == 0)
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "Class does not belong to this lesson");
        String filename = boardId.equals("board-1") ? "Общая-доска.pdf" : "Ученик-" + boardId.substring(9) + ".pdf";
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO completed_lesson_documents(id,teacher_id,event_id,class_id,board_id,filename,pdf)
            VALUES (?,?,?,?,?,?,?)
            ON CONFLICT (teacher_id,event_id,class_id,board_id)
            DO UPDATE SET pdf = EXCLUDED.pdf, filename = EXCLUDED.filename, updated_at = now()
            """, id, teacher.id(), eventId, classId, boardId, filename, bytes);
        return list(teacher, eventId).stream().filter(item -> item.classId().equals(classId) && item.boardId().equals(boardId)).findFirst().orElseThrow();
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthenticatedUser teacher,
                                            @PathVariable String eventId, @PathVariable UUID documentId) {
        lessons.require(teacher, eventId);
        return jdbc.query("""
            SELECT filename, pdf FROM completed_lesson_documents
            WHERE id = ? AND teacher_id = ? AND event_id = ?
            """, rs -> {
                if (!rs.next()) return ResponseEntity.notFound().build();
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"completed-lesson.pdf\"")
                    .body(rs.getBytes("pdf"));
            }, documentId, teacher.id(), eventId);
    }
}
