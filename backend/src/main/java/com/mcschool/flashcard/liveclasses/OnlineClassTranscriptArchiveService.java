package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.lessons.LessonPreparation;
import com.mcschool.flashcard.lessons.LessonPreparationRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes automatically ingested Soniox segments in the normal lesson's
 * Transcripts section, in addition to retaining their original segment rows.
 *
 * Never changes the teacher-authored transcript or uploaded PDF. The room's
 * calendar event ID and owner determine the target: client-supplied student
 * names and dates are never used for lesson matching.
 */
@Service
public class OnlineClassTranscriptArchiveService {
    private final LessonPreparationRepository preparations;
    private final OnlineClassTranscriptSegmentRepository segments;
    private final OnlineClassRepository classes;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OnlineClassTranscriptArchiveService(LessonPreparationRepository preparations,
            OnlineClassTranscriptSegmentRepository segments, OnlineClassRepository classes,
            JdbcTemplate jdbc, Clock clock) {
        this.preparations = preparations;
        this.segments = segments;
        this.classes = classes;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public void sync(OnlineClass room) {
        if (room.getEventId() == null || room.getEventId().isBlank()) return;

        // Several tracks and retired/replacement rooms can write the same
        // preparation. Serialize on its teacher/event key, not on the room ID.
        String key = room.getTeacher().getId() + ":" + room.getEventId();
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) row -> {}, key);
        OnlineClass current = classes.findFirstByTeacherIdAndEventIdOrderByCreatedAtDesc(
                room.getTeacher().getId(), room.getEventId()).orElse(room);
        if (!current.getId().equals(room.getId())) return;

        List<OnlineClassTranscriptSegment> finals =
                segments.findAllByOnlineClassIdOrderByStartMsAscIdAsc(room.getId());
        if (finals.isEmpty()) return;

        StringBuilder body = new StringBuilder();
        for (OnlineClassTranscriptSegment segment : finals) {
            String text = segment.getText();
            if (text == null || text.isBlank()) continue;
            if (!body.isEmpty()) body.append('\n');
            long totalSeconds = Math.max(0, segment.getStartMs() / 1000);
            body.append(String.format("[%02d:%02d:%02d] ", totalSeconds / 3600,
                    (totalSeconds / 60) % 60, totalSeconds % 60));
            String label = segment.getUser() != null ? segment.getUser().getFullName()
                    : segment.getSpeakerLabel();
            body.append(label == null || label.isBlank() ? "Участник" : label.trim());
            body.append(": ").append(text.trim());
        }
        if (body.isEmpty()) return;

        LessonPreparation preparation = preparations.findByTeacherIdAndEventId(
                        room.getTeacher().getId(), room.getEventId())
                .orElseGet(() -> LessonPreparation.create(room.getTeacher(), room.getEventId()));
        preparation.replaceSonioxTranscript(body.toString(), clock.instant());
        preparations.save(preparation);
    }
}
