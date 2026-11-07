package com.mcschool.flashcard.liveclasses;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/online-classes/{classId}/kolin")
@PreAuthorize("hasRole('TEACHER')")
public class KolinChatController {
    private final KolinChatService chat;
    public KolinChatController(KolinChatService chat) { this.chat = chat; }

    @GetMapping("/status")
    public Map<String, Boolean> status(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID classId) {
        return chat.status(caller, classId);
    }

    @PostMapping("/messages")
    public KolinChatService.Reply send(@AuthenticationPrincipal AuthenticatedUser caller, @PathVariable UUID classId,
                                      @Valid @RequestBody KolinChatService.Request request) {
        return chat.send(caller, classId, request);
    }
}
