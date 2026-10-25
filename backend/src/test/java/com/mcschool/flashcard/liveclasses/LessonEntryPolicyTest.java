package com.mcschool.flashcard.liveclasses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.mcschool.flashcard.users.User;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LessonEntryPolicyTest {
    private static final Instant DAY = Instant.parse("2026-10-24T10:00:00Z");
    private OnlineClass room(Instant start) {
        OnlineClass room = OnlineClass.forStudent(mock(User.class), "event", "binding", "Maths",
                start, start.plusSeconds(3600), mock(User.class));
        room.start(start); return room;
    }
    private OnlineClassParticipant returning(OnlineClass room) {
        OnlineClassParticipant p = OnlineClassParticipant.student(room, mock(User.class));
        p.admit(); p.markJoined(room.getScheduledStartAt()); return p;
    }
    @Test void berlinDayIsHalfOpenAtBothMidnights() {
        assertThat(LessonEntryPolicy.dayStart(DAY)).isEqualTo(Instant.parse("2026-10-23T22:00:00Z"));
        assertThat(LessonEntryPolicy.dayEnd(DAY)).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
        assertThat(LessonEntryPolicy.isLessonDay(DAY, LessonEntryPolicy.dayStart(DAY))).isTrue();
        assertThat(LessonEntryPolicy.isLessonDay(DAY, LessonEntryPolicy.dayStart(DAY).minusNanos(1))).isFalse();
        assertThat(LessonEntryPolicy.isLessonDay(DAY, LessonEntryPolicy.dayEnd(DAY).minusNanos(1))).isTrue();
        assertThat(LessonEntryPolicy.isLessonDay(DAY, LessonEntryPolicy.dayEnd(DAY))).isFalse();
    }
    @Test void daylightSavingDaysHave23And25Hours() {
        Instant spring = Instant.parse("2026-03-29T12:00:00Z"), autumn = Instant.parse("2026-10-25T12:00:00Z");
        assertThat(Duration.between(LessonEntryPolicy.dayStart(spring), LessonEntryPolicy.dayEnd(spring))).isEqualTo(Duration.ofHours(23));
        assertThat(Duration.between(LessonEntryPolicy.dayStart(autumn), LessonEntryPolicy.dayEnd(autumn))).isEqualTo(Duration.ofHours(25));
    }
    @Test void openingTimeAndLegacyExtensionsCannotMoveTheLessonDay() {
        OnlineClass room = room(DAY); room.allowStudentJoiningUntil(DAY.plusSeconds(172800));
        assertThat(LessonEntryPolicy.canEnter(room, null, DAY)).isTrue();
        assertThat(LessonEntryPolicy.canEnter(room, null, LessonEntryPolicy.dayEnd(DAY))).isFalse();
        assertThat(LessonEntryPolicy.canEnter(room, null, LessonEntryPolicy.dayStart(DAY).minusSeconds(1))).isFalse();
    }
    @Test void admissionAloneDoesNotGrantAnEntryAfterMidnight() {
        OnlineClass room = room(DAY); OnlineClassParticipant p = OnlineClassParticipant.student(room, mock(User.class)); p.admit();
        assertThat(LessonEntryPolicy.canEnter(room, p, LessonEntryPolicy.dayEnd(DAY))).isFalse();
    }
    @Test void anExistingAdmittedSessionCanReconnectAfterMidnightUntilEnd() {
        OnlineClass room = room(DAY); OnlineClassParticipant p = returning(room); Instant after = LessonEntryPolicy.dayEnd(DAY).plusSeconds(60);
        assertThat(LessonEntryPolicy.canEnter(room, p, after)).isTrue();
        assertThat(LessonEntryPolicy.state(room, p, after)).isEqualTo("RECONNECT");
        room.end(after);
        assertThat(LessonEntryPolicy.canEnter(room, p, after)).isFalse();
        assertThat(LessonEntryPolicy.state(room, p, after)).isEqualTo("ENDED");
    }
    @Test void scheduledAndCancelledRoomsNeverGrantEntry() {
        OnlineClass room = OnlineClass.forStudent(mock(User.class), "event", "binding", "Maths", DAY, DAY.plusSeconds(3600), mock(User.class));
        assertThat(LessonEntryPolicy.state(room, null, DAY)).isEqualTo("WAITING_FOR_TEACHER");
        assertThat(LessonEntryPolicy.canEnter(room, null, DAY)).isFalse();
        room.cancel(); assertThat(LessonEntryPolicy.canEnter(room, null, DAY)).isFalse();
    }
    @Test void emptyRoomsStayOpenUntilNextMondayEvenAfterTenMinutes() {
        OnlineClass room = room(DAY); room.observeEmpty(DAY.minusSeconds(7200));
        OnlineClassParticipant participant = returning(room);
        assertThat(LessonEntryPolicy.canEnter(room, participant, LessonEntryPolicy.weekEnd(DAY).minusNanos(1))).isTrue();
        assertThat(LessonEntryPolicy.weekExpired(room, LessonEntryPolicy.weekEnd(DAY).minusNanos(1))).isFalse();
        assertThat(LessonEntryPolicy.canEnter(room, participant, LessonEntryPolicy.weekEnd(DAY))).isFalse();
        assertThat(LessonEntryPolicy.state(room, participant, LessonEntryPolicy.weekEnd(DAY))).isEqualTo("ENDED");
    }
    @Test void removedAndRejectedAttendeesCannotUseReconnect() {
        OnlineClass room = room(DAY); OnlineClassParticipant p = returning(room); p.remove();
        assertThat(LessonEntryPolicy.canEnter(room, p, DAY)).isFalse();
        assertThat(LessonEntryPolicy.canEnter(room, p, LessonEntryPolicy.dayEnd(DAY))).isFalse();
        OnlineClassParticipant rejected = returning(room); rejected.reject();
        assertThat(LessonEntryPolicy.canEnter(room, rejected, DAY)).isFalse();
    }
    @Test void tokensExpireAtDayBoundaryForFirstEntryAndMondayForReturning() {
        OnlineClass room = room(DAY); Instant nearEnd = LessonEntryPolicy.dayEnd(DAY).minusSeconds(10);
        assertThat(LessonEntryPolicy.tokenTtl(room, null, nearEnd, Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofSeconds(10));
        OnlineClassParticipant p = returning(room);
        assertThat(LessonEntryPolicy.tokenTtl(room, p, nearEnd, Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofMinutes(5));
        Instant lastSecond = LessonEntryPolicy.weekEnd(DAY).minusSeconds(10);
        assertThat(LessonEntryPolicy.tokenTtl(room, p, lastSecond, Duration.ofMinutes(5)))
                .isEqualTo(Duration.ofSeconds(10));
    }
    @Test void mondayBoundaryHandlesDaylightSavingAndHostReconnect() {
        Instant sunday = Instant.parse("2026-10-25T12:00:00Z");
        assertThat(LessonEntryPolicy.weekEnd(sunday)).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
        assertThat(LessonEntryPolicy.weekEnd(Instant.parse("2026-03-29T12:00:00Z")))
                .isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
        OnlineClass room = room(DAY);
        OnlineClassParticipant teacher = OnlineClassParticipant.host(room, mock(User.class));
        assertThat(LessonEntryPolicy.canEnter(room, teacher, LessonEntryPolicy.dayEnd(DAY).plusSeconds(1))).isTrue();
    }
}
