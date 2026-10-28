package com.mcschool.flashcard.lessons;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Personal food diary tools for the administrator's separate My Skills site. */
@Service
public class PersonalNutritionMcpService {
    public static final List<String> TOOL_NAMES = List.of(
            "get_nutrition_entries", "add_nutrition_entry", "delete_nutrition_entry");
    private static final String ENTRY_PREFIX = "food-mindcrafti-";
    private final URI apiUri;
    private final HttpClient client;
    private final ObjectMapper json;

    @Autowired
    public PersonalNutritionMcpService(
            ObjectMapper json,
            @Value("${app.mcp.personal-nutrition.api-base-url:https://personal-life-api-production.up.railway.app}") String apiBaseUrl) {
        this(json, apiBaseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    PersonalNutritionMcpService(ObjectMapper json, String apiBaseUrl, HttpClient client) {
        this.json = json;
        this.client = client;
        URI base = URI.create(apiBaseUrl);
        if (!"https".equalsIgnoreCase(base.getScheme())
                && !("http".equalsIgnoreCase(base.getScheme()) && "localhost".equalsIgnoreCase(base.getHost()))) {
            throw new IllegalArgumentException("Personal nutrition API must use HTTPS");
        }
        this.apiUri = base.resolve("/api/nutrition");
    }

    public static List<Map<String, Object>> definitions() {
        Map<String, Object> date = prop("string", "YYYY-MM-DD in Europe/Berlin local time (the food diary date).");
        Map<String, Object> getArgs = schema(Map.of("date", date), List.of("date"));
        Map<String, Object> addArgs = schema(Map.ofEntries(
                Map.entry("date", date),
                Map.entry("name", prop("string", "Food or drink description; include main ingredients.")),
                Map.entry("kcal", prop("number", "Calories for the entire portion, including toppings and sauces.")),
                Map.entry("meal", prop("string", "Optional: breakfast/lunch/dinner/snack (or Russian equivalent).")),
                Map.entry("quantity", prop("number", "Optional numeric portion count, e.g. 2.")),
                Map.entry("unit", prop("string", "Optional portion unit, e.g. pieces, grams, cups.")),
                Map.entry("protein", prop("number", "Optional protein in grams, only when known.")),
                Map.entry("carbs", prop("number", "Optional carbohydrates in grams, only when known.")),
                Map.entry("fat", prop("number", "Optional fat in grams, only when known.")),
                Map.entry("notes", prop("string", "Optional details and uncertainty; note that photo-based calorie estimates are approximate.")),
                Map.entry("idempotencyKey", prop("string", "Stable identifier for this specific eating occasion. Reuse on retry, but use a different key for another portion."))
        ), List.of("date", "name", "kcal", "idempotencyKey"));
        Map<String, Object> deleteArgs = schema(Map.of(
                "date", date,
                "entryId", prop("string", "ID from get_nutrition_entries. Only ChatGPT-created entries can be deleted."),
                "confirm", prop("boolean", "Must be true after the user explicitly asks to delete this entry.")
        ), List.of("date", "entryId", "confirm"));
        return List.of(
                tool("get_nutrition_entries", "ADMIN ONLY: Read the owner's personal food diary at My Skills for a date, including entries and total calories. This is NOT school student data. Use to check a saved entry or answer daily totals.", getArgs, true, false),
                tool("add_nutrition_entry", "ADMIN ONLY: Save a food or drink directly to the owner's My Skills nutrition diary (the personal site, not the Mindcrafti school database). For photo estimates state uncertainty in notes. Use get_nutrition_entries first if unsure whether the meal was already recorded; provide a stable idempotencyKey to prevent repeat saves. Always verify save result.", addArgs, false, false),
                tool("delete_nutrition_entry", "ADMIN ONLY: Delete an individual entry previously created by this connector from the personal My Skills food diary. Only use after explicit user request and confirm=true.", deleteArgs, false, true));
    }

    public Map<String, Object> call(String name, Map<String, Object> args, UUID ownerId) {
        if (!TOOL_NAMES.contains(name)) throw new IllegalArgumentException("Unknown nutrition tool: " + name);
        if (ownerId == null) throw new IllegalArgumentException("A signed-in admin is required for personal nutrition");
        LocalDate date = parseDate(args.get("date"));
        try {
            return switch (name) {
                case "get_nutrition_entries" -> read(date);
                case "add_nutrition_entry" -> add(date, args, ownerId);
                case "delete_nutrition_entry" -> delete(date, args);
                default -> throw new IllegalArgumentException("Unknown nutrition tool: " + name);
            };
        } catch (IOException ex) {
            throw new IllegalStateException("Personal nutrition API is unavailable: " + ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Personal nutrition request was interrupted");
        }
    }

    private Map<String, Object> read(LocalDate date) throws IOException, InterruptedException {
        List<Map<String, Object>> entries = entries(date);
        double total = 0;
        for (Map<String, Object> e : entries) {
            Object raw = e.get("kcal");
            if (raw instanceof Number n) total += n.doubleValue();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("entries", entries);
        result.put("count", entries.size());
        result.put("totalKcal", Math.round(total * 10.0) / 10.0);
        return result;
    }

    private Map<String, Object> add(LocalDate date, Map<String, Object> args, UUID ownerId)
            throws IOException, InterruptedException {
        String name = requiredText(args.get("name"), "name", 200);
        double kcal = requiredNumber(args.get("kcal"), "kcal", 0, 10000);
        String key = requiredText(args.get("idempotencyKey"), "idempotencyKey", 180);
        String id = ENTRY_PREFIX + sha256(ownerId + "|" + date + "|" + key);
        Map<String, Object> existing = findById(entries(date), id);
        if (existing != null) return saved(existing, false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("date", date.toString());
        body.put("name", name);
        body.put("kcal", kcal);
        putTextIfPresent(body, args, "meal", 80);
        putTextIfPresent(body, args, "unit", 40);
        putTextIfPresent(body, args, "notes", 1000);
        for (String field : List.of("quantity", "protein", "carbs", "fat")) {
            if (args.containsKey(field) && args.get(field) != null) {
                body.put(field, requiredNumber(args.get(field), field, 0, 10000));
            }
        }
        HttpRequest request = HttpRequest.newBuilder(apiUri)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        // Even if the POST response was lost or a retry conflicted, read back from the site.
        Map<String, Object> verified = findById(entries(date), id);
        if (verified == null) throw new IllegalStateException(
                "Food was not verified on My Skills (HTTP " + response.statusCode() + ")");
        return saved(verified, response.statusCode() == 201);
    }

    private Map<String, Object> delete(LocalDate date, Map<String, Object> args)
            throws IOException, InterruptedException {
        if (!Boolean.TRUE.equals(args.get("confirm")))
            throw new IllegalArgumentException("confirm must be true after explicit user approval");
        String entryId = requiredText(args.get("entryId"), "entryId", 128);
        if (!entryId.matches("^" + ENTRY_PREFIX + "[a-f0-9]{64}$"))
            throw new IllegalArgumentException("Can only delete food entries created via Mindcrafti MCP");
        if (findById(entries(date), entryId) == null)
            throw new IllegalArgumentException("Entry not found on this date");
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(apiUri.toString() + "/" + URLEncoder.encode(entryId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(15))
                .DELETE()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2 || findById(entries(date), entryId) != null)
            throw new IllegalStateException("Could not confirm food entry deletion on My Skills");
        return Map.of("deleted", true, "entryId", entryId, "date", date.toString());
    }

    private List<Map<String, Object>> entries(LocalDate date) throws IOException, InterruptedException {
        URI uri = URI.create(apiUri + "?date=" + URLEncoder.encode(date.toString(), StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new IllegalStateException("Personal nutrition site responded HTTP " + response.statusCode());
        Object parsed = json.readValue(response.body(), Object.class);
        if (!(parsed instanceof List<?> list)) throw new IllegalStateException("Invalid nutrition data from site");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> raw)) throw new IllegalStateException("Invalid nutrition entry in response");
            Map<String, Object> item = new LinkedHashMap<>();
            for (Map.Entry<?, ?> cell : raw.entrySet()) item.put(String.valueOf(cell.getKey()), cell.getValue());
            result.add(item);
        }
        return result;
    }

    private static Map<String, Object> findById(List<Map<String, Object>> entries, String id) {
        for (Map<String, Object> item : entries)
            if (id.equals(String.valueOf(item.get("id")))) return item;
        return null;
    }

    private static Map<String, Object> saved(Map<String, Object> entry, boolean created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("verified", true);
        result.put("created", created);
        result.put("alreadyExisted", !created);
        result.put("entry", entry);
        return result;
    }

    private static void putTextIfPresent(Map<String, Object> body, Map<String, Object> args, String key, int maxLength) {
        Object raw = args.get(key);
        if (raw == null) return;
        String value = String.valueOf(raw).trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(key + " is too long");
        if (!value.isBlank()) body.put(key, value);
    }

    private static LocalDate parseDate(Object raw) {
        if (!(raw instanceof String value) || !value.matches("^\\d{4}-\\d{2}-\\d{2}$"))
            throw new IllegalArgumentException("date must use YYYY-MM-DD format");
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) throw new IllegalArgumentException("Invalid calendar date");
            return date;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("date must be a valid YYYY-MM-DD date");
        }
    }

    private static String requiredText(Object raw, String field, int maxLength) {
        if (!(raw instanceof String value) || value.trim().isEmpty() || value.trim().length() > maxLength)
            throw new IllegalArgumentException(field + " is required and must be <= " + maxLength + " characters");
        return value.trim();
    }

    private static double requiredNumber(Object raw, String field, double min, double max) {
        if (!(raw instanceof Number n)) throw new IllegalArgumentException(field + " must be a number");
        double result = n.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max)
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return result;
    }

    private static String sha256(String s) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not supported", ex);
        }
    }

    private static Map<String, Object> prop(String type, String desc) {
        return Map.of("type", type, "description", desc);
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> args, boolean readOnly, boolean destructive) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", args);
        tool.put("annotations", Map.of("readOnlyHint", readOnly, "destructiveHint", destructive,
                "idempotentHint", true, "openWorldHint", true));
        return tool;
    }
}
