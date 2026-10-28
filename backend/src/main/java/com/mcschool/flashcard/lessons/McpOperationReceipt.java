package com.mcschool.flashcard.lessons;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Internal bounded operation receipt; never exposes request arguments or credentials in logs. */
@Entity
@Table(name = "mcp_operation_receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McpOperationReceipt {
    @Id private UUID id;
    @Column(name = "actor_id", nullable = false) private UUID actorId;
    @Column(name = "teacher_id") private UUID teacherId;
    @Column(name = "scope_key", nullable = false, length = 50) private String scopeKey;
    @Column(nullable = false, length = 100) private String operation;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Column(name = "payload_hash", nullable = false, length = 64) private String payloadHash;
    @Column(name = "result_json", columnDefinition = "text") private String resultJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;

    static McpOperationReceipt locked(UUID id, String hash, String resultJson) {
        McpOperationReceipt receipt = new McpOperationReceipt();
        receipt.id = id;
        receipt.payloadHash = hash;
        receipt.resultJson = resultJson;
        return receipt;
    }
}
