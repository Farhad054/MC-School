package com.mcschool.flashcard.lessons;

import static org.junit.jupiter.api.Assertions.*;

import com.mcschool.flashcard.users.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OwnerWorkbookGeneratorServiceTest {
    private final OwnerWorkbookGeneratorService service = new OwnerWorkbookGeneratorService();
    private static final UUID NICK = UUID.fromString("14e3c7c1-1fc8-41bd-858a-6c20efdd957a");

    @Test
    void onlyOwnerOrAdminMayUsePrivatePilot() {
        assertTrue(OwnerWorkbookGeneratorService.allowed(Role.ADMIN, UUID.randomUUID(), false));
        assertTrue(OwnerWorkbookGeneratorService.allowed(Role.TEACHER, NICK, false));
        assertFalse(OwnerWorkbookGeneratorService.allowed(Role.TEACHER, UUID.randomUUID(), false));
        assertFalse(OwnerWorkbookGeneratorService.allowed(Role.STUDENT, NICK, false));
        assertFalse(OwnerWorkbookGeneratorService.allowed(Role.ADMIN, NICK, true));
        assertFalse(OwnerWorkbookGeneratorService.allowed(null, NICK, false));
    }

    @Test
    void manifestAndContentAreReadOnlyAndNoPathTraversal() throws Exception {
        Map<String,Object> metadata = service.source("");
        assertEquals("CHATGPT_OWNER_PILOT", metadata.get("mode"));
        assertTrue(((java.util.List<?>)metadata.get("files")).contains("diagrams.mjs"));
        assertEquals("integration/railway-and-ux", metadata.get("sourceBranch"));
        Map<String,Object> js = service.source("generate.mjs");
        assertTrue(((String) js.get("content")).contains("checkAnswerMath"));
        assertEquals(64, ((String) js.get("sha256")).length());
        Map<String,Object> diagrams = service.source("diagrams.mjs");
        assertTrue(((String)diagrams.get("content")).contains("geometrySvg"));
        Map<String,Object> demo = service.source("examples/graphs-geometry-demo.json");
        assertTrue(((String)demo.get("content")).contains("quadratic"));
        assertThrows(IllegalArgumentException.class, () -> service.source("../application.yml"));
        assertThrows(IllegalArgumentException.class, () -> service.source("assets/logo-m.png"));
    }
}
