package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.users.Role;
import com.mcschool.flashcard.users.User;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Read-only ChatGPT workbook-layout pilot. It does not create lessons or publish PDFs.
 * Teacher prompts and normal PDF endpoints are deliberately left unchanged.
 */
@Service
public class OwnerWorkbookGeneratorService {
    private static final String VERSION = "2026-10-19-v3-clean-diagrams";
    private static final String RESOURCE_PREFIX = "owner-workbook-generator/";
    private static final String REPO_PATH = "backend/src/main/resources/owner-workbook-generator/";
    private static final UUID NICK_TEACHER_ID = UUID.fromString("14e3c7c1-1fc8-41bd-858a-6c20efdd957a");
    private static final List<String> TEXT_FILES = List.of(
            "README.md", "CHANGELOG.md", "generate.mjs", "diagrams.mjs", "diagrams.test.mjs",
            "styles.css", "lesson.sample.json", "examples/graphs-geometry-demo.json", "examples/clean-tasks-demo.json",
            "render_pdf.py", "verify_pdf.py", "package.json");
    private static final Set<String> ALLOWED_FILES = Set.copyOf(TEXT_FILES);

    /** ADMIN and Nick's specifically enrolled teacher account only; no API-key access. */
    static boolean allowed(Role role, UUID userId, boolean apiKey) {
        return !apiKey && role != null && (role == Role.ADMIN
                || (role == Role.TEACHER && NICK_TEACHER_ID.equals(userId)));
    }

    public boolean allowed(User user, boolean apiKey) {
        return user != null && allowed(user.getRole(), user.getId(), apiKey);
    }

    /** No filename returns the current version + file catalog; filename returns a single source. */
    public Map<String, Object> source(String filename) throws IOException {
        String file = filename == null ? "" : filename.trim();
        if (file.isEmpty()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("version", VERSION);
            info.put("mode", "CHATGPT_OWNER_PILOT");
            info.put("files", new ArrayList<>(TEXT_FILES));
            info.put("sourceBranch", "integration/railway-and-ux");
            info.put("sourceDirectory", REPO_PATH);
            info.put("generation", "Use generator + diagrams.mjs inside ChatGPT to create two PDFs; no site UI or server-side execution.");
            info.put("diagrams", "SVG graphs and 2D/3D geometry. Default triangle labels: ABC, abc, αβγ; numeric data below in Gegeben/Gesucht; PDF review required.");
            info.put("publication", "Never publish or assign homework without the user's explicit approval.");
            info.put("branding", "Fetch the current get_brand_guide before generating. Reuse the original M logo.");
            info.put("teacherPrompts", "Unchanged; this tool is not available to other teachers.");
            return info;
        }
        if (!ALLOWED_FILES.contains(file))
            throw new IllegalArgumentException("Unknown workbook generator file. Use the files returned by the manifest.");
        byte[] contents = new ClassPathResource(RESOURCE_PREFIX + file).getInputStream().readAllBytes();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("file", file);
        result.put("contentType", "text/plain; charset=utf-8");
        result.put("sha256", sha256(contents));
        result.put("content", new String(contents, StandardCharsets.UTF_8));
        return result;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
