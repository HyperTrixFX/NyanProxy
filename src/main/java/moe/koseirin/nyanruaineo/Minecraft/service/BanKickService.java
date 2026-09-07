package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import org.springframework.stereotype.Component;

/**
 * 封禁后的在线玩家即时踢出。V3 / V7 封禁接口（以及 {@code /ban} 命令）写入封禁记录后，
 * 调用本服务把当前仍在线的命中玩家用「游戏阶段」断开连接数据包踢出（显示封禁画面，
 * 与后续登录拦截画面一致）。
 */
@Slf4j
@Component
public class BanKickService {

    private final MinecraftProxy proxy;
    private final ProxyBanService proxyBanService;
    private final PlayerKickService playerKickService;

    public BanKickService(MinecraftProxy proxy, ProxyBanService proxyBanService, PlayerKickService playerKickService) {
        this.proxy = proxy;
        this.proxyBanService = proxyBanService;
        this.playerKickService = playerKickService;
    }

    /**
     * 踢出所有命中该封禁目标的在线玩家，返回踢出人数。
     * <p>
     * 目标匹配：对每个在线玩家按其 Minecraft UUID 反查封禁目标（Yggdrasil → NyanID UID，
     * 否则 → Mojang UUID），只有 {@code targetType} 与 {@code uid} 都匹配才踢出。
     */
    public int kickOnlinePlayers(BanUserList ban) {
        if (ban == null || ban.getUid() == null) {
            return 0;
        }
        int targetType = ban.getTargetType() == null ? BanUserList.TARGET_UID : ban.getTargetType();
        int count = 0;
        for (UserConnection user : proxy.getOnlineUsers()) {
            if (user == null || user.getUuid() == null || user.getChannel() == null || !user.getChannel().isActive()) {
                continue;
            }
            ProxyBanService.BanTarget target = proxyBanService.resolveTarget(user.getUuid());
            if (target == null || target.targetType() != targetType || !target.value().equals(ban.getUid())) {
                continue;
            }
            playerKickService.disconnect(user, proxyBanService.renderBanMessage(ban, user.getUsername()));
            log.info("Kicked banned player {} ({})", user.getUsername(), user.getUuid());
            count++;
        }
        return count;
    }
}
