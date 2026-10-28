package com.mcschool.flashcard.liveclasses;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Weekly Monday 00:00 Berlin cleanup; empty rooms remain open for reconnects. */
@Component
public class OnlineClassIdleScheduler {
    private static final Logger log = LoggerFactory.getLogger(OnlineClassIdleScheduler.class);
    private final OnlineClassRepository repository;
    private final OnlineClassService service;
    private final OnlineClassProperties properties;

    public OnlineClassIdleScheduler(OnlineClassRepository repository, OnlineClassService service,
                                    OnlineClassProperties properties) {
        this.repository = repository;
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.online-classes.idle-sweep-ms:15000}",
               initialDelayString = "${app.online-classes.idle-sweep-initial-ms:15000}")
    public void sweep() {
        if (!properties.enabled()) return;
        try {
            LinkedHashSet<UUID> ids = new LinkedHashSet<>();
            repository.findAllByStatusIn(List.of(OnlineClassStatus.LOBBY_OPEN, OnlineClassStatus.LIVE))
                    .forEach(room -> ids.add(room.getId()));
            repository.findAllByMediaClosePendingTrue().forEach(room -> ids.add(room.getId()));
            for (UUID id : ids) {
                try { service.reconcileIdleSession(id); }
                catch (RuntimeException ex) {
                    // Neither credentials nor upstream response bodies are logged.
                    log.warn("Could not verify room presence; leaving the session unchanged: classId={}", id);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Could not list rooms for idle cleanup; will retry");
        }
    }
}
