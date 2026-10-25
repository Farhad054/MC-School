package com.mcschool.flashcard.liveclasses;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Calendar-day admission is independent of lesson duration and actual opening time. */
public final class LessonEntryPolicy {
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private LessonEntryPolicy() {}

    public static LocalDate lessonDate(Instant scheduledStart) {
        return scheduledStart.atZone(ZONE).toLocalDate();
    }

    public static Instant dayStart(Instant scheduledStart) {
        return lessonDate(scheduledStart).atStartOfDay(ZONE).toInstant();
    }

    public static Instant dayEnd(Instant scheduledStart) {
        // Calendar arithmetic, not +24h: Berlin has 23h and 25h DST days.
        return lessonDate(scheduledStart).plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    public static boolean isLessonDay(Instant scheduledStart, Instant now) {
        return !now.isBefore(dayStart(scheduledStart)) && now.isBefore(dayEnd(scheduledStart));
    }

    /** Monday 00:00 Berlin after the week containing the scheduled lesson. */
    public static Instant weekEnd(Instant scheduledStart) {
        return lessonDate(scheduledStart).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(ZONE).toInstant();
    }

    public static boolean weekExpired(OnlineClass room, Instant now) {
        return !now.isBefore(weekEnd(room.getScheduledStartAt()));
    }

    public static boolean returning(OnlineClass room, OnlineClassParticipant participant) {
        // Admission alone is not a previous entry. markJoined is only reached
        // after a successful, authorized connection-token request.
        return participant != null && participant.isAdmitted() &&
                (participant.isHost() || (participant.getFirstJoinedAt() != null
                    && isLessonDay(room.getScheduledStartAt(), participant.getFirstJoinedAt())));
    }

    public static boolean canEnter(OnlineClass room, OnlineClassParticipant participant, Instant now) {
        if (!room.isConnectable() || weekExpired(room, now)) return false;
        if (participant != null && (participant.getAdmissionState() == AdmissionState.REMOVED
                || participant.getAdmissionState() == AdmissionState.REJECTED)) return false;
        return isLessonDay(room.getScheduledStartAt(), now)
                || (!now.isBefore(dayEnd(room.getScheduledStartAt())) && returning(room, participant));
    }

    public static String state(OnlineClass room, OnlineClassParticipant participant, Instant now) {
        if (room.isTerminal()) return "ENDED";
        if (weekExpired(room, now)) return "ENDED";
        if (canEnter(room, participant, now)) {
            return isLessonDay(room.getScheduledStartAt(), now) ? "OPEN" : "RECONNECT";
        }
        if (!isLessonDay(room.getScheduledStartAt(), now)) return "DAY_CLOSED";
        if (!room.isConnectable()) return "WAITING_FOR_TEACHER";
        return "CLOSED";
    }

    public static Duration tokenTtl(OnlineClass room, OnlineClassParticipant participant,
                                    Instant now, Duration configured) {
        Duration ttl = configured.compareTo(Duration.between(now, weekEnd(room.getScheduledStartAt()))) < 0
                ? configured : Duration.between(now, weekEnd(room.getScheduledStartAt()));
        if (!returning(room, participant)) {
            Duration remaining = Duration.between(now, dayEnd(room.getScheduledStartAt()));
            if (remaining.compareTo(ttl) < 0) ttl = remaining;
        }
        return ttl;
    }
}
