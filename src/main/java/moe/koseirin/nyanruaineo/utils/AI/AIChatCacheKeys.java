package moe.koseirin.nyanruaineo.utils.AI;

import java.time.Duration;

/**
 * Redis 中 AI 会话缓存的键与 TTL 约定。
 * <p>
 * 这里只集中放常量，不改变原有缓存结构：会话历史仍然是
 * {@code romyu:chat:history:<sessionId>} 的 Redis List。新增的 meta hash 只用于记录
 * 「这条会话属于哪个 union_openid」，以便到期前提取长期记忆。
 */
public final class AIChatCacheKeys {

    private AIChatCacheKeys() {
    }

    public static final String HISTORY_PREFIX = "romyu:chat:history:";
    public static final String META_PREFIX = "romyu:chat:meta:";

    public static final String META_OWNER_OPENID = "ownerOpenid";
    public static final String META_PLATFORM = "platform";
    public static final String META_DISPLAY_NAME = "displayName";

    /** 与原实现保持一致：会话缓存 5 小时。 */
    public static final Duration EXPIRE_DURATION = Duration.ofHours(5);

    /** 剩余 TTL 小于等于该值时提取记忆。 */
    public static final long DEFAULT_EXTRACT_BEFORE_SECONDS = 600L;

    /** 单次模型上下文最多带多少条历史消息（完整历史仍保留在 Redis）。 */
    public static final int MAX_CONTEXT_MESSAGES = 40;

    public static String historyKey(String sessionId) {
        return HISTORY_PREFIX + sessionId;
    }

    public static String metaKey(String sessionId) {
        return META_PREFIX + sessionId;
    }
}
