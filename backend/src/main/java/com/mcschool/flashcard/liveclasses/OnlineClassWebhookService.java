package com.mcschool.flashcard.liveclasses;

import io.livekit.server.WebhookReceiver;
import com.mcschool.flashcard.liveclasses.provider.LiveClassMediaProvider;
import com.mcschool.flashcard.liveclasses.provider.MediaProviderException;
import java.util.Optional;
import java.util.UUID;
import livekit.LivekitEgress;
import livekit.LivekitWebhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies and applies LiveKit webhooks.
 *
 * <p>Events are the authoritative source for recording completion and for
 * reconciling participant presence. They may arrive duplicated or out of order,
 * so every handler here is idempotent.
 */
@Service
public class OnlineClassWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OnlineClassWebhookService.class);

    private final OnlineClassProperties properties;
    private final OnlineClassRecordingService recordingService;
    private final OnlineClassRepository classRepository;
    private final OnlineClassParticipantRepository participantRepository;
    private final java.time.Clock clock;
    private final LiveClassMediaProvider mediaProvider;

    public OnlineClassWebhookService(OnlineClassProperties properties,
                                     OnlineClassRecordingService recordingService,
                                     OnlineClassRepository classRepository,
                                     OnlineClassParticipantRepository participantRepository,
                                     java.time.Clock clock, LiveClassMediaProvider mediaProvider) {
        this.properties = properties;
        this.recordingService = recordingService;
        this.classRepository = classRepository;
        this.participantRepository = participantRepository;
        this.clock = clock;
        this.mediaProvider = mediaProvider;
    }

    /**
     * Verifies the raw body and Authorization header.
     *
     * <p>A new receiver is constructed per call rather than cached, so a
     * credential change takes effect without a restart. Verification must happen
     * before the body is parsed or any state is read.
     */
    public LivekitWebhook.WebhookEvent verify(String rawBody, String authorization) {
        if (!properties.isMediaConfigured()) {
            throw new WebhookVerificationException("Provider webhooks are not configured");
        }
        if (authorization == null || authorization.isBlank()) {
            throw new WebhookVerificationException("Missing webhook authorization");
        }
        try {
            WebhookReceiver receiver = new WebhookReceiver(
                    properties.livekitApiKey(), properties.livekitApiSecret());
            // skipAuth is never passed: the default performs JWT + body-hash checks.
            return receiver.receive(rawBody, authorization);
        } catch (Exception e) {
            throw new WebhookVerificationException("Webhook verification failed", e);
        }
    }

    /** Dispatches a verified event. Unknown event types are ignored. */
    @Transactional
    public void handle(LivekitWebhook.WebhookEvent event) {
        String type = event.getEvent();
        log.info("Provider webhook received: event={}", type);

        switch (type) {
            case "egress_started" -> applyEgress(event, OnlineClassRecordingService.EgressOutcome.ACTIVE);
            case "egress_ended", "egress_updated" -> applyEgressTerminal(event);
            case "participant_joined" -> applyParticipantPresence(event, true);
            case "participant_left" -> applyParticipantPresence(event, false);
            case "room_finished" -> applyRoomFinished(event);
            default -> log.debug("Ignoring unhandled provider event type");
        }
    }

    private void applyEgress(LivekitWebhook.WebhookEvent event,
                             OnlineClassRecordingService.EgressOutcome outcome) {
        if (!event.hasEgressInfo()) {
            return;
        }
        recordingService.applyEgressUpdate(event.getEgressInfo().getEgressId(), outcome,
                null, null, null, null);
    }

    /**
     * Egress completion. The provider reports COMPLETE or FAILED/ABORTED; an
     * event still describing an active egress is treated as a progress update.
     */
    private void applyEgressTerminal(LivekitWebhook.WebhookEvent event) {
        if (!event.hasEgressInfo()) {
            return;
        }
        LivekitEgress.EgressInfo info = event.getEgressInfo();
        LivekitEgress.EgressStatus status = info.getStatus();

        if (status == LivekitEgress.EgressStatus.EGRESS_COMPLETE) {
            String objectKey = null;
            Long byteSize = null;
            Long durationSeconds = null;
            if (info.getFileResultsCount() > 0) {
                LivekitEgress.FileInfo file = info.getFileResults(0);
                objectKey = file.getFilename();
                byteSize = file.getSize();
                // Provider reports nanoseconds; store whole seconds.
                durationSeconds = file.getDuration() / 1_000_000_000L;
            }
            recordingService.applyEgressUpdate(info.getEgressId(),
                    OnlineClassRecordingService.EgressOutcome.COMPLETED,
                    objectKey, byteSize, durationSeconds, null);
        } else if (status == LivekitEgress.EgressStatus.EGRESS_FAILED
                || status == LivekitEgress.EgressStatus.EGRESS_ABORTED) {
            recordingService.applyEgressUpdate(info.getEgressId(),
                    OnlineClassRecordingService.EgressOutcome.FAILED,
                    null, null, null, info.getError());
        } else if (status == LivekitEgress.EgressStatus.EGRESS_ACTIVE) {
            recordingService.applyEgressUpdate(info.getEgressId(),
                    OnlineClassRecordingService.EgressOutcome.ACTIVE, null, null, null, null);
        }
    }

    /**
     * Reconciles attendance from provider presence.
     *
     * <p>The participant row is matched by the user id embedded in the identity,
     * so a client cannot attribute presence to someone else.
     */
    private void applyParticipantPresence(LivekitWebhook.WebhookEvent event, boolean joined) {
        if (!event.hasRoom() || !event.hasParticipant()) {
            return;
        }
        Optional<OnlineClass> onlineClass = classRepository.findByRoomName(event.getRoom().getName());
        Optional<UUID> userId = ParticipantIdentity.userId(event.getParticipant().getIdentity());
        if (onlineClass.isEmpty() || userId.isEmpty()) {
            return;
        }
        OnlineClass room = classRepository.findForUpdate(onlineClass.get().getId()).orElse(null);
        if (room == null) return;
        if (room.isTerminal()) {
            if (joined) {
                // A cached token must not resurrect a room closed by End.
                room.setMediaClosePending(true);
                try { mediaProvider.removeParticipant(room.getRoomName(), event.getParticipant().getIdentity()); }
                catch (MediaProviderException ex) { log.warn("Terminal room disconnect will be retried: classId={}", room.getId()); }
            }
            return;
        }
        participantRepository
                .findByOnlineClassIdAndUserId(room.getId(), userId.get())
                .ifPresent(participant -> {
                    if (joined && participant.isAdmitted()) {
                        java.time.Instant occurredAt = event.getCreatedAt() > 0
                                ? java.time.Instant.ofEpochSecond(event.getCreatedAt()) : clock.instant();
                        if (occurredAt.isAfter(clock.instant())) occurredAt = clock.instant();
                        if (room.getEmptySince() == null || !occurredAt.isBefore(room.getEmptySince())) room.observeOccupied();
                        if (!participant.isConnected()) participant.markJoined(occurredAt);
                    } else if (!joined) {
                        // markLeft ignores an unmatched leave, so a duplicate or
                        // out-of-order event cannot inflate attendance.
                        participant.markLeft(clock.instant());
                    }
                });
    }

    /** Closes any still-open attendance spans when the room goes away. */
    private void applyRoomFinished(LivekitWebhook.WebhookEvent event) {
        if (!event.hasRoom()) {
            return;
        }
        classRepository.findByRoomName(event.getRoom().getName()).ifPresent(onlineClass ->
                participantRepository
                        .findAllByOnlineClassIdOrderByCreatedAtAsc(onlineClass.getId())
                        .forEach(participant -> {
                            if (participant.isConnected()) {
                                participant.markLeft(clock.instant());
                            }
                        }));
    }
}
