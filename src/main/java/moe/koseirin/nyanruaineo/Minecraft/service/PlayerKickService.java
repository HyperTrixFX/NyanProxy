package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.KickMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;
import moe.koseirin.nyanruaineo.Minecraft.util.DisconnectMessageRenderer;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;

/**
 * 以可自定义的踢出界面将玩家踢出代理端。
 * 消息模板位于 {@code proxy.kick-message} 数据库配置中（{@code kick_message_base} 字段，
 * 与封禁模板 {@code proxy.ban-message} 分离），支持 {@code &} 颜色代码、
 * {@code \n}/{@code |} 换行符以及 {@code $playerName} / {@code $reason} / {@code $kickId} 占位符，
 * 在关闭连接之前会通过版本适配的游戏阶段 Disconnect 数据包发送。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerKickService {

    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private final SecureRandom random = new SecureRandom();

    private final MinecraftProxy proxy;
    private final ProxyProperties properties;

    /** Kicks one player with the configured kick screen and the given reason. */
    public void kick(UserConnection user, String reason, String kid) {
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            return;
        }
        if (kid == null){
            kid = randomId();
        }
        String message = buildMessage(user.getUsername(), reason == null ? "" : reason , kid);
        log.info("Kicking {}: {}", user.getUsername(), reason);
        disconnect(user, message);
    }

    /**
     * 以指定消息（{@code §} 颜色码文本）向玩家发送「游戏阶段」断开连接数据包并关闭连接。
     * 与登录阶段的 {@code Kick(0x00)} 不同，游戏阶段必须使用 play 状态的 Disconnect 数据包，
     * 否则客户端会直接显示「连接中断」而不是自定义消息。
     */
    public void disconnect(UserConnection user, String message) {
        if (user == null || user.getChannel() == null || !user.getChannel().isActive()) {
            return;
        }
        int protocolVersion = user.getProtocolVersion();

        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt(kickPacketId(protocolVersion), buf);
        JSONObject component = ChatComponentUtils.component(message == null ? "" : message);
        if (protocolVersion >= 765) {                                  // 1.20.3+ NBT component
            ChatComponentUtils.writeNbtComponent(buf, component);
        } else {
            DefinedPacket.writeString(component.toJSONString(), buf);
        }

        // Flush the disconnect screen first, then tear the connection down.
        user.getChannel().writeAndFlush(buf).addListener(future -> user.close());
    }

    /** Kicks every online player with the configured kick screen. */
    public void kickAll(String reason,String kid) {
        for (UserConnection user : proxy.getOnlineUsers()) {
            try {
                kick(user, reason,kid);
            } catch (Exception e) {
                log.warn("Failed to kick {}", user.getUsername(), e);
            }
        }
    }

    /**
     * Builds the kick screen text from the configured {@code kick_message_base} template:
     * line breaks ({@code \n} or {@code |}), {@code &} → {@code §} colour codes and the
     * {@code $playerName}/{@code $reason}/{@code $kickId} placeholders.
     */
    private String buildMessage(String playerName, String reason, String kid) {
        KickMessageConfig config = properties.getKickMessageConfig();
        String template = (config.isEnabled() && config.getKickMessageBase() != null)
                ? config.getKickMessageBase()
                : "&cYou have been kicked from the proxy!\n&7Reason: &f$reason";
        return DisconnectMessageRenderer.render(template, Map.of(
                "$playerName", playerName == null ? "" : playerName,
                "$reason", reason == null ? "" : reason,
                "$kickId", kid == null ? "" : kid));
    }

    private String randomId() {
        StringBuilder id = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            id.append(ID_CHARS.charAt(random.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }

    /**
     * 按协议版本区分的客户端游戏阶段断开连接数据包 ID；
     * 未明确列出条目的版本会继承相邻的较低版本）。
     */
    private static int kickPacketId(int protocolVersion) {
        if (protocolVersion >= 773) {
            return 0x20;                                               // 1.21.9+
        }
        if (protocolVersion >= 770) {
            return 0x1C;                                               // 1.21.5+
        }
        if (protocolVersion >= 766) {
            return 0x1D;                                               // 1.20.5+
        }
        if (protocolVersion >= 764) {
            return 0x1B;                                               // 1.20.2-1.20.4
        }
        if (protocolVersion >= 762) {
            return 0x1A;                                               // 1.19.4-1.20.1
        }
        if (protocolVersion >= 761) {
            return 0x17;                                               // 1.19.3
        }
        if (protocolVersion >= 760) {
            return 0x19;                                               // 1.19.1-1.19.2
        }
        if (protocolVersion >= 759) {
            return 0x17;                                               // 1.19
        }
        if (protocolVersion >= 755) {
            return 0x1A;                                               // 1.17-1.18.2
        }
        if (protocolVersion >= 751) {
            return 0x19;                                               // 1.16.2-1.16.5
        }
        if (protocolVersion >= 735) {
            return 0x1A;                                               // 1.16-1.16.1
        }
        if (protocolVersion >= 573) {
            return 0x1B;                                               // 1.15-1.15.2
        }
        if (protocolVersion >= 477) {
            return 0x1A;                                               // 1.14-1.14.4
        }
        if (protocolVersion >= 393) {
            return 0x1B;                                               // 1.13-1.13.2
        }
        if (protocolVersion >= 107) {
            return 0x1A;                                               // 1.9-1.12.2
        }
        return 0x40;                                                   // 1.8-1.8.9
    }
}
