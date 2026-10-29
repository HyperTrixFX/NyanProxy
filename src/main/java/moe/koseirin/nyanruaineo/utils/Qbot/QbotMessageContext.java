package moe.koseirin.nyanruaineo.utils.Qbot;

import java.util.function.Consumer;

/**
 * 一条已解析的 QQ 消息上下文。
 *
 * @param unionOpenid QQ union_openid（长期记忆唯一键）
 * @param displayName 对用户的称呼
 * @param content     原始消息内容
 * @param group       是否群聊
 * @param groupOpenid 群 openid（私聊为 null）
 * @param messageId   消息 ID
 * @param reply       回复函数（内部会做长度拆分）
 */
public record QbotMessageContext(
        String unionOpenid,
        String displayName,
        String content,
        boolean group,
        String groupOpenid,
        String messageId,
        Consumer<String> reply
) {
    public void send(String text) {
        if (reply != null && text != null && !text.isBlank()) {
            reply.accept(text);
        }
    }
}
