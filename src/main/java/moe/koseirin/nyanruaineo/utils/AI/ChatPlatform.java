package moe.koseirin.nyanruaineo.utils.AI;

/**
 * AI 对话来源。用于给模型提供“当前是在私聊、群聊还是网页”的上下文，
 * 也用于决定是否注册长期记忆的归属者。
 */
public enum ChatPlatform {
    QQ_PRIVATE,
    QQ_GROUP,
    WEB;

    public boolean isGroup() {
        return this == QQ_GROUP;
    }

    public String displayName() {
        return switch (this) {
            case QQ_PRIVATE -> "QQ 私聊";
            case QQ_GROUP -> "QQ 群聊";
            case WEB -> "网页";
        };
    }
}
