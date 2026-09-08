package moe.koseirin.nyanruaineo.services.impl;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.service.BanKickService;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerQueryService;
import moe.koseirin.nyanruaineo.Minecraft.service.ProxyBanService;
import moe.koseirin.nyanruaineo.dto.BackendServerStatusDTO;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.utils.Respond;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * v7 后端操作业务逻辑（{@code /api/v7/backend/**}）。鉴权由
 * {@code ServerListService.authenticate} 负责，这里处理被委托的代理操作：
 * 封禁/解封（写入 {@link BanUserList}）、踢出、广播、转移、在线玩家/服务器查询。
 * <p>
 * 踢出/转移/服务器列表复用 {@link ProxyFuncImpl} 已加固的逻辑，封禁复用
 * {@link ProxyBanService}（按 Minecraft UUID 反查 NyanID UID 或直接封 UUID）。
 */
@Service
public class BackendApiFuncImpl {

    private final ProxyFuncImpl proxyFunc;
    private final PlayerQueryService playerQueryService;
    private final ProxyBanService proxyBanService;
    private final BanKickService banKickService;
    private final BanUserRepository banUserRepository;
    private final MinecraftProxy proxy;
    private final Respond respond;

    public BackendApiFuncImpl(ProxyFuncImpl proxyFunc,
                              PlayerQueryService playerQueryService,
                              ProxyBanService proxyBanService,
                              BanKickService banKickService,
                              BanUserRepository banUserRepository,
                              MinecraftProxy proxy,
                              Respond respond) {
        this.proxyFunc = proxyFunc;
        this.playerQueryService = playerQueryService;
        this.proxyBanService = proxyBanService;
        this.banKickService = banKickService;
        this.banUserRepository = banUserRepository;
        this.proxy = proxy;
        this.respond = respond;
    }

    /** 当前所有子服务器及其在线状态/人数/玩家列表。 */
    public List<BackendServerStatusDTO> listServers() {
        return proxyFunc.getAllServers();
    }

    /** 当前所有在线玩家快照。 */
    public List<PlayerQueryService.PlayerInfo> listPlayers() {
        return playerQueryService.getOnlinePlayers();
    }

    /** 按 Minecraft UUID 封禁（委托给代理，写入封禁表）。 */
    public ResponseEntity<?> ban(String uuid, String reason, Integer type, String expire, String bannedBy) {
        if (uuid == null || uuid.isBlank()) {
            return badRequest("uuid is required");
        }
        UUID mcUuid = parseUuid(uuid.trim());
        if (mcUuid == null) {
            return badRequest("Invalid uuid: " + uuid);
        }
        ProxyBanService.BanTarget target = proxyBanService.resolveTarget(mcUuid);
        if (target == null) {
            return badRequest("Cannot resolve ban target for uuid: " + uuid);
        }

        int banType = type == null ? BanUserList.TYPE_GAME_BAN : type;
        if (banType != BanUserList.TYPE_GAME_BAN && banType != BanUserList.TYPE_DEAD_BAN) {
            return badRequest("Unsupported ban type: " + type + " (allowed: "
                    + BanUserList.TYPE_GAME_BAN + ", " + BanUserList.TYPE_DEAD_BAN + ")");
        }
        LocalDateTime expireTime = parseExpire(expire);
        if (expire != null && !expire.isBlank() && expireTime == null) {
            return badRequest("Invalid expire format, use ISO-8601 e.g. 2025-01-01T00:00:00");
        }

        BanUserList saved = proxyBanService.ban(target, reason, expireTime, bannedBy, banType);
        int kicked = banKickService.kickOnlinePlayers(saved);
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "message", "Banned",
                "banId", saved.getBanID(),
                "target", saved.getUid(),
                "targetType", saved.getTargetType(),
                "type", saved.getType(),
                "expireTime", saved.getExpireTime(),
                "kicked", kicked,
                "timestamp", LocalDateTime.now());
    }

    /** 按 Minecraft UUID 解封（解除该目标所有生效封禁）。 */
    public ResponseEntity<?> unban(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return badRequest("uuid is required");
        }
        UUID mcUuid = parseUuid(uuid.trim());
        if (mcUuid == null) {
            return badRequest("Invalid uuid: " + uuid);
        }
        ProxyBanService.BanTarget target = proxyBanService.resolveTarget(mcUuid);
        if (target == null) {
            return badRequest("Cannot resolve ban target for uuid: " + uuid);
        }
        int affected = banUserRepository.deactivateByUid(target.value());
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "message", "Unbanned",
                "affected", affected,
                "target", target.value(),
                "timestamp", LocalDateTime.now());
    }

    /** 校验某个 UUID 当前是否由本代理转发（后端连接认证用）。 */
    public ResponseEntity<?> verify(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return badRequest("uuid is required");
        }
        UUID mcUuid = parseUuid(uuid.trim());
        if (mcUuid == null) {
            return badRequest("Invalid uuid: " + uuid);
        }
        boolean verified = proxy.isUuidKnown(mcUuid);
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "uuid", mcUuid.toString(),
                "verified", verified,
                "timestamp", LocalDateTime.now());
    }

    /** 踢出在线玩家（复用代理已加固的逻辑）。 */
    public ResponseEntity<?> kick(String player, String reason) {
        return proxyFunc.kickPlayer(player, reason);
    }

    /** 转移在线玩家到其他子服务器（复用代理已加固的逻辑）。 */
    public ResponseEntity<?> transfer(String player, String targetServer) {
        return proxyFunc.transferPlayer(player, targetServer);
    }

    /** 向所有在线玩家广播消息。 */
    public ResponseEntity<?> broadcast(String message) {
        if (message == null || message.isBlank()) {
            return badRequest("message is required");
        }
        String trimmed = message.trim();
        if (trimmed.length() > 512) {
            return badRequest("message too long (max 512)");
        }
        int recipients = proxy.broadcast(trimmed);
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "message", "Broadcast sent",
                "recipients", recipients,
                "timestamp", LocalDateTime.now());
    }

    /** 兼容带/不带连字符的 Minecraft UUID。 */
    private UUID parseUuid(String s) {
        String t = s;
        if (t.length() == 32 && !t.contains("-")) {
            t = t.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5");
        }
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private LocalDateTime parseExpire(String expire) {
        if (expire == null || expire.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(expire.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 400, "message", message, "timestamp", LocalDateTime.now());
    }
}
