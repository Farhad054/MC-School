package com.mcschool.flashcard.lessons;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL unique claims wait for concurrent transactions before acquiring the receipt row lock. */
@Repository
public class McpOperationReceiptRepository {
    private final JdbcTemplate jdbc;

    public McpOperationReceiptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Claim(McpOperationReceipt receipt, boolean inserted) {}

    public Claim claimAndLock(UUID actorId, UUID teacherId, String operation, String key, String hash) {
        String scope = teacherId == null ? "school" : "teacher:" + teacherId;
        int inserted = jdbc.update("""
                INSERT INTO mcp_operation_receipts
                    (id, actor_id, teacher_id, scope_key, operation, idempotency_key, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (actor_id, scope_key, operation, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), actorId, teacherId, scope, operation, key, hash);
        McpOperationReceipt receipt = jdbc.queryForObject("""
                SELECT id, payload_hash, result_json FROM mcp_operation_receipts
                WHERE actor_id = ? AND scope_key = ? AND operation = ? AND idempotency_key = ?
                FOR UPDATE
                """, (rs, row) -> McpOperationReceipt.locked(rs.getObject("id", UUID.class),
                        rs.getString("payload_hash"), rs.getString("result_json")),
                actorId, scope, operation, key);
        return new Claim(receipt, inserted == 1);
    }

    public void complete(UUID id, String json) {
        int changed = jdbc.update("UPDATE mcp_operation_receipts SET result_json = ?, completed_at = CURRENT_TIMESTAMP WHERE id = ?", json, id);
        if (changed != 1) throw new IllegalStateException("Operation receipt no longer exists");
    }
}
