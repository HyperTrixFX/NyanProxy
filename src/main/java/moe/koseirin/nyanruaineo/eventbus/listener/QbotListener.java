package moe.koseirin.nyanruaineo.eventbus.listener;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson.JSONObject;
import io.github.kloping.qqbot.api.v2.FriendMessageEvent;
import io.github.kloping.qqbot.api.v2.GroupMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.eventbus.Interface.EventHeader;
import moe.koseirin.nyanruaineo.eventbus.SysEvent.QbotFriendMessageReceivedEvent;
import moe.koseirin.nyanruaineo.eventbus.SysEvent.QbotGroupMessageReceivedEvent;
import moe.koseirin.nyanruaineo.services.QbotCommandService;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotMessageContext;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotTextSender;
import org.springframework.stereotype.Component;

/**
 * 把 QQ 好友/群消息整理成统一上下文后交给 {@link QbotCommandService}。
 * <p>
 * 原实现里好友与群聊各有一份 switch，修改命令时容易漏改；现在监听器只负责取字段和发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QbotListener {

    private final QbotCommandService commandService;

    @EventHeader
    private void friendMessageListener(QbotFriendMessageReceivedEvent received) {
        FriendMessageEvent event = received.event();
        try {
            JSONObject metadata = event.getMetadata();
            JSONObject author = metadata == null ? null : metadata.getJSONObject("author");
            String unionOpenid = author == null ? null : author.getString("union_openid");
            if (unionOpenid == null || unionOpenid.isBlank()) {
                log.error("Qbot friend message has no union_openid. metadata={}",
                        metadata == null ? "null" : metadata.toJSONString());
                safeSend(event, "呜……咱暂时认不出你是谁喵，稍后再试一次吧。");
                return;
            }
            String content = metadata.getString("content");
            String messageId = metadata.getString("id");
            String displayName = commandService.resolveDisplayName(unionOpenid);
            QbotMessageContext context = new QbotMessageContext(
                    unionOpenid,
                    displayName,
                    content,
                    false,
                    null,
                    messageId,
                    text -> QbotTextSender.send(event, text)
            );
            commandService.handle(context);
        } catch (Exception e) {
            log.error("Failed to handle Qbot friend message", e);
            safeSend(event, "呜……咱刚刚处理消息时卡住了喵，再试一次好不好？");
        }
    }

    @EventHeader
    private void groupMessageListener(QbotGroupMessageReceivedEvent received) {
        GroupMessageEvent event = received.event();
        try {
            JSONObject metadata = event.getMetadata();
            JSONObject author = metadata == null ? null : metadata.getJSONObject("author");
            String unionOpenid = author == null ? null : author.getString("union_openid");
            if (unionOpenid == null || unionOpenid.isBlank()) {
                log.error("Qbot group message has no union_openid. metadata={}",
                        metadata == null ? "null" : metadata.toJSONString());
                safeSend(event, "呜……咱暂时认不出你是谁喵，稍后再试一次吧。");
                return;
            }
            String content = metadata.getString("content");
            String messageId = metadata.getString("id");
            String groupOpenid = metadata.getString("group_openid");
            if ((groupOpenid == null || groupOpenid.isBlank()) && event.getGroup() != null) {
                groupOpenid = event.getGroup().getOpenid();
            }
            String displayName = commandService.resolveDisplayName(unionOpenid);
            QbotMessageContext context = new QbotMessageContext(
                    unionOpenid,
                    displayName,
                    content,
                    true,
                    groupOpenid,
                    messageId,
                    text -> QbotTextSender.send(event, text)
            );
            commandService.handle(context);
        } catch (Exception e) {
            log.error("Failed to handle Qbot group message", e);
            safeSend(event, "呜……咱刚刚处理消息时卡住了喵，再试一次好不好？");
        }
    }

    private void safeSend(io.github.kloping.qqbot.api.Sender sender, String text) {
        try {
            QbotTextSender.send(sender, text);
        } catch (Exception e) {
            log.warn("Failed to send Qbot error reply", e);
        }
    }
}
