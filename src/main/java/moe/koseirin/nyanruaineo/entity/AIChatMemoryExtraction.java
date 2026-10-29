package moe.koseirin.nyanruaineo.entity;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 临时会话的记忆提取进度。
 * <p>
 * Redis 会话每次写入都会把 TTL 重置为 5 小时；提取任务在剩余 TTL ≤ 10 分钟时运行。
 * 本表保存「已经处理到第几条消息」，避免同一个缓存窗口内重复提取，也避免用户随后继续聊天时
 * 只提取到旧内容。
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "ai_chat_memory_extraction")
public class AIChatMemoryExtraction {

    /** Redis 会话 ID；QQ 场景下就是 union_openid。 */
    @Id
    @Column(name = "session_id", columnDefinition = "varchar(128)", nullable = false)
    private String sessionId;

    @Column(name = "owner_openid", columnDefinition = "varchar(128)", nullable = false)
    private String ownerOpenid;

    /** 已处理的 Redis List 消息条数。 */
    @Column(name = "extracted_message_count", nullable = false)
    private int extractedMessageCount;

    @Column(name = "extracted_at", nullable = false)
    private LocalDateTime extractedAt;

    /** AI 或 FALLBACK。 */
    @Column(name = "last_source_type", columnDefinition = "varchar(16)")
    private String lastSourceType;

    /** 最近一次提取失败的简要原因，便于排查；成功时清空。 */
    @Column(name = "last_error", columnDefinition = "varchar(255)")
    private String lastError;
}
