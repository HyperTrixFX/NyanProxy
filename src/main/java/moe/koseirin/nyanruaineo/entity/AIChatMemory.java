package moe.koseirin.nyanruaineo.entity;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * AI 长期记忆。
 * <p>
 * 临时会话仍保存在 Redis（{@code romyu:chat:history:*}，5 小时 TTL）；只有当缓存接近过期时，
 * 才会抽取其中的稳定信息写入本表。{@link #ownerOpenid} 是 QQ 的 union_openid，记忆检索只允许
 * 查询当前会话自己的记录。
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        name = "ai_chat_memory",
        indexes = {
                @Index(name = "idx_ai_memory_owner_updated", columnList = "owner_openid, updated_at")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_ai_memory_owner_hash", columnNames = {"owner_openid", "content_hash"})
        }
)
public class AIChatMemory {

    /** 记忆类别：用户画像。 */
    public static final String TYPE_PROFILE = "profile";
    /** 记忆类别：偏好/习惯。 */
    public static final String TYPE_PREFERENCE = "preference";
    /** 记忆类别：重要事件。 */
    public static final String TYPE_EVENT = "event";
    /** 记忆类别：关系/称呼。 */
    public static final String TYPE_RELATIONSHIP = "relationship";
    /** 记忆类别：其它。 */
    public static final String TYPE_OTHER = "other";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** QQ union_openid，长期记忆的唯一归属者。 */
    @Column(name = "owner_openid", columnDefinition = "varchar(128)", nullable = false)
    private String ownerOpenid;

    @Column(name = "memory_type", columnDefinition = "varchar(32)", nullable = false)
    private String memoryType;

    /** 用第三人称记录的简洁事实，例如「用户喜欢在深夜玩生存服」。 */
    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    /** 逗号分隔的检索关键词。 */
    @Column(name = "keywords", columnDefinition = "varchar(512)")
    private String keywords;

    /** 1（可忽略）~ 5（非常重要）。 */
    @Column(name = "importance", nullable = false)
    private int importance = 1;

    /** 产生这条记忆的临时会话 ID（QQ 场景下与 owner_openid 相同）。 */
    @Column(name = "source_session", columnDefinition = "varchar(128)")
    private String sourceSession;

    /** AI（结构化提取）或 FALLBACK（原始片段兜底）。 */
    @Column(name = "source_type", columnDefinition = "varchar(16)", nullable = false)
    private String sourceType = "AI";

    /** owner + 类别 + 内容 的 SHA-256，用于跨会话去重。 */
    @Column(name = "content_hash", columnDefinition = "varchar(64)", nullable = false)
    private String contentHash;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
