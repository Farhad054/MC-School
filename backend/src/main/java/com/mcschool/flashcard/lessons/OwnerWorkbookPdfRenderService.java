package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.settings.SchoolBrandGuideService;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Secure adapter between teacher-authorized MCP tools and the Git-versioned SVG/PDF
 * renderer. No source code, template files or shell commands come from the model.
 * Generated files are only stored as unpublished teacher-owned lesson drafts.
 */
@Service
public class OwnerWorkbookPdfRenderService {
    static final int MAX_INPUT_CHARS = 115_000;
    static final int MAX_DOCUMENT_BYTES = 15 * 1024 * 1024;
    static final String VERSION = "2026-11-05-production";
    private static final List<String> RENDER_FILES = List.of(
            "generate.mjs", "math.mjs", "schema.mjs", "diagrams.mjs", "styles.css", "render_pdf.py", "verify_pdf.py", "algebra_qa.py");

    @Value("${MINDCRAFTI_WORKBOOK_MODULES:/opt/mindcrafti-workbook/node_modules}")
    private String modulesDirectory;

    private final ObjectMapper mapper;
    private final LessonPdfDraftService drafts;
    private final SchoolBrandGuideService brandGuide;

    public OwnerWorkbookPdfRenderService(ObjectMapper mapper, LessonPdfDraftService drafts,
                                         SchoolBrandGuideService brandGuide) {
        this.mapper = mapper;
        this.drafts = drafts;
        this.brandGuide = brandGuide;
    }

    public Map<String, Object> create(AuthenticatedUser teacher, String eventId,
                                      String lessonJson, String planSummary,
                                      String difficultySummary) throws Exception {
        Objects.requireNonNull(teacher, "teacher");
        if (eventId == null || eventId.isBlank())
            throw new IllegalArgumentException("eventId is required");
        if (lessonJson == null || lessonJson.isBlank() || lessonJson.length() > MAX_INPUT_CHARS)
            throw new IllegalArgumentException("lessonJson length must be 1..115000 characters");
        @SuppressWarnings("unchecked")
        Map<String, Object> document = mapper.readValue(lessonJson, Map.class);
        int exerciseCount = validateDocument(document);

        BufferedImage officialLogo = LessonPdfDraftService.extractOfficialLogo(
                brandGuide.pdfForStaff(teacher).bytes());
        Path workspace = Files.createTempDirectory("mindcrafti-workbook-v3-");
        try {
            for (String name : RENDER_FILES) {
                try (var input = new ClassPathResource("owner-workbook-generator/" + name).getInputStream()) {
                    Files.copy(input, workspace.resolve(name));
                }
            }
            Path modules = Path.of(modulesDirectory).toAbsolutePath().normalize();
            if (!Files.isDirectory(modules.resolve("mathjax")))
                throw new IllegalStateException("Workbook MathJax runtime is unavailable; configure MINDCRAFTI_WORKBOOK_MODULES");
            Files.createSymbolicLink(workspace.resolve("node_modules"), modules);
            Path logoPath = workspace.resolve("logo.png");
            if (!ImageIO.write(officialLogo, "png", logoPath.toFile()))
                throw new IllegalStateException("Cannot encode official school logo");
            Path dataPath = workspace.resolve("lesson.json");
            Files.writeString(dataPath, lessonJson, StandardCharsets.UTF_8);
            Path output = Files.createDirectory(workspace.resolve("out"));

            String duration = String.valueOf(((Number) requireMap(document.get("meta"), "meta")
                    .get("durationMinutes")).intValue());
            String basename = "Mindcrafti_Workbook_v3";
            ProcessBuilder pb = new ProcessBuilder("node", "generate.mjs",
                    "--lesson=" + dataPath, "--out=" + output,
                    "--basename=" + basename, "--logo=" + logoPath);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Path logs = workspace.resolve("renderer.log");
            pb.redirectOutput(logs.toFile());
            Process job;
            try {
                job = pb.start();
            } catch (IOException ex) {
                throw new IllegalStateException("Workbook v3 runtime is not installed: node/python3/WeasyPrint/PyMuPDF required", ex);
            }
            boolean finished;
            try {
                finished = job.waitFor(115, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                job.descendants().forEach(ProcessHandle::destroyForcibly);
                job.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Workbook rendering was interrupted", ex);
            }
            if (!finished) {
                job.descendants().forEach(ProcessHandle::destroyForcibly);
                job.destroyForcibly();
                throw new IllegalStateException("Workbook rendering exceeded 115 seconds");
            }
            if (job.exitValue() != 0) {
                String fullLog = Files.readString(logs, StandardCharsets.UTF_8);
                throw new IllegalArgumentException("Workbook generation/QA failed: " +
                        fullLog.substring(Math.max(0, fullLog.length() - 1800)));
            }
            Path workbookPath=output.resolve(basename + "_Arbeitsheft_" + duration + "min.pdf");
            Path answersPath=output.resolve(basename + "_Lehrerloesungen_" + duration + "min.pdf");
            if(Files.size(workbookPath)>MAX_DOCUMENT_BYTES || Files.size(answersPath)>MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("One generated PDF exceeds the 15 MB limit");
            byte[] workbook = Files.readAllBytes(workbookPath);
            byte[] answers = Files.readAllBytes(answersPath);
            if (workbook.length > MAX_DOCUMENT_BYTES || answers.length > MAX_DOCUMENT_BYTES)
                throw new IllegalArgumentException("One generated PDF exceeds the 15 MB limit");
            @SuppressWarnings("unchecked") Map<String,Object> mathQa=mapper.readValue(Files.readString(output.resolve(basename+"_math-qa.json")),Map.class);
            if(!Boolean.TRUE.equals(mathQa.get("passed"))) throw new IllegalStateException("Renderer math QA report did not pass");
            Map<String, Object> result = drafts.createFromRenderer(teacher, eventId, workbook,
                    answers, planSummary, difficultySummary, exerciseCount, officialLogo);
            result.put("generator", "Mindcrafti SVG/A4 workbook " + VERSION);
            result.put("lessonDurationMinutes", Integer.parseInt(duration));
            result.put("durationMinutes", Integer.parseInt(duration));
            result.put("workbook", Map.of("filename", result.get("workbookFilename"), "sizeBytes", workbook.length));
            result.put("answers", Map.of("filename", result.get("answersFilename"), "sizeBytes", answers.length));
            Map<String,Object> qa=new java.util.LinkedHashMap<>();
            qa.put("mathPassed",true);
            qa.put("layoutPassed",true);
            qa.put("rawLatexFound",false);
            qa.put("algebraChecked",mathQa.getOrDefault("checked",0));
            qa.put("algebraCoverage",mathQa.getOrDefault("coverage","explicit assertions only"));
            qa.put("arithmeticChains",mathQa.getOrDefault("arithmeticChains",0));
            qa.put("graphWarnings",mathQa.getOrDefault("graphWarnings",List.of()));
            result.put("qa",qa);
            result.put("published", false);
            result.put("publication", "DRAFT only; review both PDFs before calling publish_lesson_draft");
            return result;
        } finally {
            try (var paths = Files.walk(workspace)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }

    static int validateDocument(Map<String, Object> document) {
        for (String key : List.of("homework", "homeworkDays", "homeworkPdf", "assignment")) {
            if (document.containsKey(key))
                throw new IllegalArgumentException("Homework belongs to a separate workflow");
        }
        Map<?, ?> meta = requireMap(document.get("meta"), "meta");
        Object n = meta.get("durationMinutes");
        if (!(n instanceof Number minutes) || minutes.intValue() < 30 || minutes.intValue() > 240
                || minutes.doubleValue() != minutes.intValue())
            throw new IllegalArgumentException("meta.durationMinutes must be an integer from 30 to 240");
        String school = String.valueOf(meta.get("school"));
        if (!"MINDCRAFTI SCHOOL".equalsIgnoreCase(school.trim()))
            throw new IllegalArgumentException("meta.school must be MINDCRAFTI SCHOOL");
        List<?> studentPages = requirePages(document.get("workbookPages"), "workbookPages", 18);
        List<?> teacherPages = requirePages(document.get("teacherPages"), "teacherPages", 12);
        int count = 0;
        for (Object page : studentPages) {
            Map<?, ?> p = requireMap(page, "student page");
            for (Object item : requireBlocks(p)) {
                count += inspectStudentTree(requireMap(item, "student block"), 0);
            }
        }
        for (Object page : teacherPages) {
            Map<?, ?> p = requireMap(page, "teacher page");
            for (Object block : requireBlocks(p)) requireMap(block, "teacher block");
        }
        if (count < 1 || count > 120) throw new IllegalArgumentException("Workbook needs 1..120 student exercises");
        return count;
    }

    /** Inspect all nested layout containers, not only the top-level block list. */
    private static int inspectStudentTree(Object raw, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Workbook layout is too deeply nested");
        int count = 0;
        if (raw instanceof Map<?,?> map) {
            String type = String.valueOf(map.get("type"));
            if (List.of("solutions", "schedule", "teacherNote").contains(type))
                throw new IllegalArgumentException("Teacher-only block in student PDF: " + type);
            if ("exercise".equals(type) || "taskCard".equals(type))
                count = map.get("rows") instanceof List<?> rows && !rows.isEmpty() ? rows.size() : 1;
            for (Object value : map.values()) if (value instanceof Map<?,?> || value instanceof List<?>)
                count += inspectStudentTree(value, depth + 1);
        } else if (raw instanceof List<?> list) {
            if (list.size() > 200) throw new IllegalArgumentException("Workbook collection is too large");
            for (Object value : list) count += inspectStudentTree(value, depth + 1);
        }
        return count;
    }

    private static Map<?, ?> requireMap(Object raw, String name) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be an object");
        return map;
    }
    private static List<?> requirePages(Object raw, String name, int max) {
        if (!(raw instanceof List<?> pages) || pages.isEmpty() || pages.size() > max)
            throw new IllegalArgumentException(name + " must contain 1.." + max + " pages");
        return pages;
    }
    private static List<?> requireBlocks(Map<?, ?> page) {
        if (!(page.get("blocks") instanceof List<?> blocks) || blocks.isEmpty() || blocks.size() > 35)
            throw new IllegalArgumentException("Each page must contain 1..35 blocks");
        return blocks;
    }
}
