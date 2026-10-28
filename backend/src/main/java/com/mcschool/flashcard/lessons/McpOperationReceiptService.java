package com.mcschool.flashcard.lessons;

import com.mcschool.flashcard.auth.AuthenticatedUser;
import com.mcschool.flashcard.users.Role;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Atomic local writes and separately claimed external actions; no process-local locks or cached authority. */
@Service
public class McpOperationReceiptService {
    private static final int MAX_RESULT_BYTES = 256 * 1024;
    private final McpAccessService access;
    private final McpOperationReceiptRepository receipts;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;

    public McpOperationReceiptService(McpAccessService access, McpOperationReceiptRepository receipts, ObjectMapper json,
                                      PlatformTransactionManager transactions) {
        this.access = access;
        this.receipts = receipts;
        this.json = json;
        this.transactions = transactions;
    }

    /**
     * Remote effects cannot be rolled back with PostgreSQL. Commit the intent
     * first, then commit local changes and the result in a second transaction.
     * An incomplete intent is never automatically taken over or re-executed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Object executeExternal(AuthenticatedUser actor, UUID effectiveTeacherId, String operation, String key,
                                  Map<String, Object> normalizedArgs, Runnable recheckTargets, Supplier<Object> work) {
        TransactionTemplate independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var claim = independent.execute(status -> {
            AuthenticatedUser current = validateExternalContext(actor, effectiveTeacherId, operation, key,
                    normalizedArgs, recheckTargets, work);
            String hash = fingerprint(normalizedArgs);
            var locked = receipts.claimAndLock(current.id(), effectiveTeacherId, operation, key, hash);
            if (!locked.receipt().getPayloadHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This idempotencyKey was already used with different arguments");
            }
            // Includes a fresh read after any wait on a concurrent claimant.
            validateExternalContext(actor, effectiveTeacherId, operation, key, normalizedArgs, recheckTargets, work);
            return locked;
        });
        if (claim == null) throw new IllegalStateException("External operation claim was not created");
        if (claim.receipt().getResultJson() != null) return json.readValue(claim.receipt().getResultJson(), Object.class);
        if (!claim.inserted()) throw new ExternalOperationPendingException();

        AtomicBoolean actionStarted = new AtomicBoolean();
        try {
            return independent.execute(status -> {
                validateExternalContext(actor, effectiveTeacherId, operation, key, normalizedArgs, recheckTargets, work);
                actionStarted.set(true);
                Object result = work.get();
                String saved = json.writeValueAsString(result);
                if (saved.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
                    throw invalid("Operation result is too large for a receipt; return bounded metadata");
                }
                receipts.complete(claim.receipt().getId(), saved);
                return json.readValue(saved, Object.class);
            });
        } catch (RuntimeException failure) {
            // The separately committed claim survives the action transaction's
            // rollback, including commit/serialization failures. Do not expose
            // provider, payload or SQL details and never retry the remote work.
            if (actionStarted.get()) throw new ExternalOperationPendingException();
            throw failure;
        }
    }

    public static class ExternalOperationPendingException extends ResponseStatusException {
        public ExternalOperationPendingException() {
            super(HttpStatus.CONFLICT, "External operation is in progress or its outcome is unknown. Inspect the current website state. Use a new key only for a newly explicitly requested action.");
        }
    }

    private AuthenticatedUser validateExternalContext(AuthenticatedUser actor, UUID teacherId, String operation,
            String key, Map<String, Object> normalizedArgs, Runnable recheckTargets, Supplier<Object> work) {
        AuthenticatedUser current = access.requireCurrentActor(actor);
        if (teacherId != null) access.requireTeacher(current, teacherId);
        else if (current.role() != Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "School-wide operations require an administrator");
        if (operation == null || !operation.matches("[a-z][a-z0-9_]{0,99}")) throw invalid("Invalid operation name");
        if (key == null || key.isBlank() || key.length() > 128 || key.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("idempotencyKey must contain 1 to 128 printable characters");
        }
        if (normalizedArgs == null || recheckTargets == null || work == null) throw invalid("Operation context is required");
        recheckTargets.run();
        return current;
    }

    @Transactional
    public Object execute(AuthenticatedUser actor, UUID effectiveTeacherId, String operation, String key,
                          Map<String, Object> normalizedArgs, Runnable recheckTargets, Supplier<Object> work) {
        AuthenticatedUser currentActor = access.requireCurrentActor(actor);
        if (effectiveTeacherId != null) access.requireTeacher(currentActor, effectiveTeacherId);
        else if (currentActor.role() != Role.ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "School-wide operations require an administrator");
        if (operation == null || !operation.matches("[a-z][a-z0-9_]{0,99}")) throw invalid("Invalid operation name");
        if (key == null || key.isBlank() || key.length() > 128 || key.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("idempotencyKey must contain 1 to 128 printable characters");
        }
        if (normalizedArgs == null || recheckTargets == null || work == null) throw invalid("Operation context is required");
        recheckTargets.run();
        String hash = fingerprint(normalizedArgs);
        var claim = receipts.claimAndLock(currentActor.id(), effectiveTeacherId, operation, key, hash);
        var receipt = claim.receipt();
        if (!receipt.getPayloadHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This idempotencyKey was already used with different arguments");
        // A claim may have waited for another writer: revalidate eligibility and
        // target ownership again before returning its now-committed result.
        access.requireCurrentActor(currentActor);
        if (effectiveTeacherId != null) access.requireTeacher(currentActor, effectiveTeacherId);
        recheckTargets.run();
        if (receipt.getResultJson() != null) return json.readValue(receipt.getResultJson(), Object.class);
        if (!claim.inserted()) throw new ResponseStatusException(HttpStatus.CONFLICT, "This operation is not complete; retry later");
        Object result = work.get();
        String saved = json.writeValueAsString(result);
        if (saved.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) throw invalid("Operation result is too large for a receipt; return bounded metadata");
        receipts.complete(receipt.getId(), saved);
        return json.readValue(saved, Object.class);
    }

    private String fingerprint(Map<String, Object> args) {
        Object tree = json.convertValue(args, Object.class);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
                json.writeValue(stream, canonical(tree, 0));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | java.io.IOException failure) {
            throw new IllegalStateException("Could not fingerprint operation arguments", failure);
        }
    }

    private Object canonical(Object value, int depth) {
        if (depth > 64) throw invalid("Operation arguments are too deeply nested");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid("Argument keys must be strings");
                ordered.put(key, canonical(entry.getValue(), depth + 1));
            }
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> ordered = new ArrayList<>();
            for (Object entry : list) ordered.add(canonical(entry, depth + 1));
            return ordered;
        }
        if (value instanceof Number number) {
            try { return new BigDecimal(number.toString()).stripTrailingZeros(); }
            catch (NumberFormatException invalidNumber) { throw invalid("Argument numbers must be finite"); }
        }
        return value;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
