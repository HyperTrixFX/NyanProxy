package moe.koseirin.nyanruaineo.utils.AI;

/**
 * 一次 AI 对话的上下文。
 *
 * @param sessionId      Redis 会话 ID；QQ 场景下与原实现一致，直接使用 union_openid
 * @param ownerOpenid    长期记忆归属者；QQ 场景下为 union_openid，网页/未知场景可为 null
 * @param userMessage    用户原始消息（不含注入的用户信息前缀）
 * @param displayName    对用户的称呼（绑定后可使用 NyanID 用户名）
 * @param platform       来源平台
 */
public record ChatContext(
        String sessionId,
        String ownerOpenid,
        String userMessage,
        String displayName,
        ChatPlatform platform
) {
    public ChatContext {
        if (platform == null) {
            platform = ChatPlatform.WEB;
        }
    }

    public boolean hasMemoryOwner() {
        return ownerOpenid != null && !ownerOpenid.isBlank();
    }
}
