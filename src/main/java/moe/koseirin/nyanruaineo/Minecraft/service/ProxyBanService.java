package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BanMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;
import moe.koseirin.nyanruaineo.Minecraft.util.DisconnectMessageRenderer;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 代理端封禁服务：把游戏登录封禁（type 5/6）接入 MinecraftProxy。
 * <p>
 * 封禁目标按来源区分：
 * <ul>
 *     <li>Yggdrasil（NyanID 外置登录）玩家 —— 通过 Minecraft UUID 反查 NyanID UID 后按 UID 封禁；</li>
 *     <li>正版（Mojang）玩家 —— 直接按其 UUID 封禁。</li>
 * </ul>
 * 查询时同时忽略已过期（{@code ExpireTime <= now}）的封禁，因此即便后台的自动解封任务还没跑，
 * 过期封禁也不会拦截登录。
 */
@Slf4j
@Service
public class ProxyBanService {

    public static final int TYPE_GAME_BAN = BanUserList.TYPE_GAME_BAN;
    public static final int TYPE_DEAD_BAN = BanUserList.TYPE_DEAD_BAN;

    private final ProxyProperties properties;
    private final BanUserRepository banUserRepository;
    private final YggdrasilRepository yggdrasilRepository;

    public ProxyBanService(ProxyProperties properties, BanUserRepository banUserRepository, YggdrasilRepository yggdrasilRepository) {
        this.properties = properties;
        this.banUserRepository = banUserRepository;
        this.yggdrasilRepository = yggdrasilRepository;
    }

    /** 封禁目标：类型 + 标识（UID 或 UUID）。 */
    public record BanTarget(int targetType, String value) {
    }

    /** 返回玩家仍在生效的游戏登录封禁，没有则返回 {@code null}。 */
    @Transactional(readOnly = true)
    public BanUserList findGameBan(UUID mcUuid) {
        if (mcUuid == null) {
            return null;
        }
        BanTarget target = resolveTarget(mcUuid);
        List<BanUserList> bans = banUserRepository.findActiveGameBans(
                target.value(), target.targetType(), LocalDateTime.now());
        return bans.isEmpty() ? null : bans.getFirst();
    }

    /** 把一个 Minecraft 玩家解析为封禁目标：Yggdrasil 玩家 → UID，否则 → UUID。 */
    @Transactional(readOnly = true)
    public BanTarget resolveTarget(UUID mcUuid) {
        if (mcUuid == null) {
            return null;
        }
        String uid = yggdrasilRepository.findNyanUidByUuid(mcUuid.toString());
        if (uid != null) {
            return new BanTarget(BanUserList.TARGET_UID, uid);
        }
        return new BanTarget(BanUserList.TARGET_UUID, mcUuid.toString().replace("-", ""));
    }

    /** 新增一条封禁记录并返回。 */
    @Transactional
    public BanUserList ban(BanTarget target, String reason, LocalDateTime expireTime, String bannedBy, int type) {
        BanUserList ban = new BanUserList();
        ban.setBanID(generateBanId());
        ban.setUid(target.value());
        ban.setTargetType(target.targetType());
        ban.setReason(reason == null || reason.isBlank() ? "Banned by an operator" : reason);
        ban.setActive(true);
        ban.setType(type);
        ban.setBanTime(LocalDateTime.now());
        ban.setBannedBy(bannedBy == null || bannedBy.isBlank() ? "Proxy" : bannedBy);
        ban.setExpireTime(expireTime);
        BanUserList saved = banUserRepository.save(ban);
        log.info("Banned {} (type={}, targetType={}) by {}: {}",
                target.value(), type, target.targetType(), saved.getBannedBy(), saved.getReason());
        return saved;
    }

    /** 渲染封禁画面为 {@code §} 颜色码文本（游戏阶段断开连接与登录阶段 Kick 共用）。 */
    public String renderBanMessage(BanUserList ban, String playerName) {
        BanMessageConfig config = properties.getBanMessageConfig();
        String template = (config.isEnabled() && config.getBannedMessageBase() != null)
                ? config.getBannedMessageBase()
                : "&cYou are banned!\n&7Reason: &f$reason";
        String expire = ban.getExpireTime() == null ? "永久" : ban.getExpireTime().toString();
        return DisconnectMessageRenderer.render(template, Map.of(
                "$playerName", playerName == null ? "" : playerName,
                "$reason", ban.getReason() == null ? "" : ban.getReason(),
                "$banId", ban.getBanID() == null ? "" : ban.getBanID(),
                "$expireTime", expire));
    }

    /** 构造登录阶段（Kick 0x00）的封禁踢出画面 JSON 字符串。 */
    public String buildBanKickJson(BanUserList ban, String playerName) {
        return ChatComponentUtils.component(renderBanMessage(ban, playerName)).toJSONString();
    }

    private String generateBanId() {
        long value = UUID.randomUUID().getLeastSignificantBits() & Long.MAX_VALUE;
        return String.format("%013d", value % 10_000_000_000_000L);
    }
}
