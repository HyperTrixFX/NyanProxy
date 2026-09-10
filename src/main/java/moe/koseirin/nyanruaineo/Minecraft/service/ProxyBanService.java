package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BanMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;
import moe.koseirin.nyanruaineo.Minecraft.util.DisconnectMessageRenderer;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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
    private final AccountsRepository accountsRepository;

    /** 封禁查询结果的缓存时长（正负结果都缓存）。 */
    private static final long BAN_CACHE_MILLIS = 30_000L;
    /** 查询失败（数据库异常）时的短缓存，避免数据库故障期间被反复冲击，同时能较快恢复。 */
    private static final long BAN_ERROR_CACHE_MILLIS = 5_000L;
    private static final int MAX_BAN_CACHE_ENTRIES = 4096;

    private record CachedBan(BanUserList ban, long expiresAt) {
    }

    private final ConcurrentHashMap<UUID, CachedBan> gameBanCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CompletableFuture<CachedBan>> inFlightBanChecks = new ConcurrentHashMap<>();

    /**
     * 专门用于把阻塞的数据库查询搬离 Netty 事件循环的线程池。
     * 用守护线程，避免关服时被这些线程挂住；队列无界但查询本身很快，
     * 且被 {@code inFlightBanChecks} 去重，不会堆积。
     */
    private final ExecutorService banLookupExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "nyanid-ban-lookup");
        thread.setDaemon(true);
        return thread;
    });

    public ProxyBanService(ProxyProperties properties, BanUserRepository banUserRepository, YggdrasilRepository yggdrasilRepository, AccountsRepository accountsRepository) {
        this.properties = properties;
        this.banUserRepository = banUserRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.accountsRepository = accountsRepository;
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

    /**
     * {@link #findGameBan} 的异步版本：<b>绝不可以</b>在 Netty 事件循环线程上直接调用
     * {@link #findGameBan}，它是阻塞的 JDBC 查询（一次登录最多三条：UID 反查、绑定反查、封禁查询）。
     * 在事件循环上同步查库会让该 worker 线程上的<b>所有</b>玩家一起卡住 —— 并发登录时
     * 所有事件循环都被按在数据库 I/O 上，还会一起抢 Hikari 连接池。
     * <p>
     * 结果按 UUID 缓存 {@value #BAN_CACHE_MILLIS} 毫秒（正负结果都缓存），所以正常重连不会
     * 反复打数据库，封禁生效最多延迟这么久。查询失败按「未封禁」处理（fail-open）：数据库
     * 抖动不应该把所有人锁在门外，失败结果只缓存 {@value #BAN_ERROR_CACHE_MILLIS} 毫秒以便快速恢复。
     * <p>
     * 注意：查询在独立线程上执行，因此 {@link #findGameBan} 上的 {@code @Transactional}
     * 不会生效（自调用不经过 Spring 代理）；三条查询各自作为一个短的只读事务执行，
     * 也就是说不会把连接池连接跨异步边界一直握着。
     */
    public CompletableFuture<BanUserList> findGameBanAsync(UUID mcUuid) {
        if (mcUuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        CachedBan cached = gameBanCache.get(mcUuid);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(cached.ban());
        }
        // 同一个 UUID 的并发登录（重连风暴）共用同一次查询，避免缓存击穿。
        CompletableFuture<CachedBan> lookup;
        try {
            lookup = inFlightBanChecks.computeIfAbsent(mcUuid,
                    uuid -> CompletableFuture.supplyAsync(() -> lookupBan(uuid), banLookupExecutor));
        } catch (RejectedExecutionException e) {
            // 关服过程中线程池已停：按未封禁放行，让正在登录的玩家走完流程。
            return CompletableFuture.completedFuture(null);
        }
        return lookup.whenComplete((result, error) -> inFlightBanChecks.remove(mcUuid, lookup))
                .thenApply(result -> {
                    cacheBan(mcUuid, result);
                    return result.ban();
                });
    }

    /** 真正执行阻塞查询的那一步，运行在 {@link #banLookupExecutor} 上；任何失败都转成可缓存的结果。 */
    private CachedBan lookupBan(UUID mcUuid) {
        try {
            return new CachedBan(findGameBan(mcUuid), System.currentTimeMillis() + BAN_CACHE_MILLIS);
        } catch (Exception e) {
            log.warn("Game ban lookup failed for {}, treating the player as unbanned: {}",
                    mcUuid, e.toString());
            return new CachedBan(null, System.currentTimeMillis() + BAN_ERROR_CACHE_MILLIS);
        }
    }

    private void cacheBan(UUID mcUuid, CachedBan result) {
        if (gameBanCache.size() > MAX_BAN_CACHE_ENTRIES) {
            long now = System.currentTimeMillis();
            gameBanCache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
            if (gameBanCache.size() > MAX_BAN_CACHE_ENTRIES) {
                gameBanCache.clear();
            }
        }
        gameBanCache.put(mcUuid, result);
    }

    @PreDestroy
    public void shutdown() {
        banLookupExecutor.shutdownNow();
    }

    /** 把一个 Minecraft 玩家解析为封禁目标：Yggdrasil 玩家 → UID；绑定正版玩家 → UID；否则 → UUID。 */
    @Transactional(readOnly = true)
    public BanTarget resolveTarget(UUID mcUuid) {
        if (mcUuid == null) {
            return null;
        }
        String uid = yggdrasilRepository.findNyanUidByUuid(mcUuid.toString());
        if (uid != null) {
            return new BanTarget(BanUserList.TARGET_UID, uid);
        }
        // 绑定了 Minecraft 账号的正版玩家：按 NyanID uid 解析，使封禁/解封/申诉都落到账户上
        String boundUid = accountsRepository.GetUidByBind(mcUuid.toString().replace("-", ""));
        if (boundUid != null) {
            return new BanTarget(BanUserList.TARGET_UID, boundUid);
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
