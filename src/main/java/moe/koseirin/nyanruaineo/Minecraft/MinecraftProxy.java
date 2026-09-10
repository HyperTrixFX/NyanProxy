package moe.koseirin.nyanruaineo.Minecraft;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.service.*;
import moe.koseirin.nyanruaineo.eventbus.EventBus;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.handler.InitialHandler;
import moe.koseirin.nyanruaineo.Minecraft.netty.FirewallHandler;
import moe.koseirin.nyanruaineo.Minecraft.netty.HandlerBoss;
import moe.koseirin.nyanruaineo.Minecraft.netty.PipelineUtils;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabListHeaderFooter;
import moe.koseirin.nyanruaineo.Minecraft.util.LogThrottle;
import moe.koseirin.nyanruaineo.services.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 这个类是 Minecraft 代理服务器的启动入口。
 * 它负责管理 Netty 的事件循环线程，绑定端口监听客户端的连接，
 * 并且每收到一个新连接，就会把它接入到握手/登录处理管道中。
 *
 */
@Slf4j
@Component
public class MinecraftProxy {

    @Getter
    private final ProxyProperties properties;
    @Getter
    private final PlayerAuthService playerAuthService;
    @Getter
    private final PingResponseProvider pingResponseProvider;
    @Getter
    private final BackendServerManager backendServerManager;
    @Getter
    private final EventBus eventBus;
    @Getter
    private final TabListService tabListService;
    @Getter
    private final PlayerMessageService playerMessageService;
    @Getter
    private final PlayerStateService playerStateService;
    @Getter
    private final PluginMessageService pluginMessageService;
    @Getter
    private final moe.koseirin.nyanruaineo.Minecraft.command.CommandManager commandManager;
    @Getter
    private final FirewallService firewallService;
    @Getter
    private final PermissionService permissionService;
    @Getter
    private final ProxyBanService proxyBanService;

    private final PlayerQueryService playerQueryService;

    /**
     * 后端把玩家连接关闭时的兜底：强制送回大厅，进不去则踢出。
     * 该服务自身依赖 MinecraftProxy，因此用 {@code @Lazy} 打破构造环。
     */
    @Getter
    private final PlayerTransferService playerTransferService;

    /** 以配置好的踢出界面把玩家踢出代理端（游戏阶段的 Disconnect 数据包）。 */
    @Getter
    private final PlayerKickService playerKickService;

    @Value("${NyanidSetting.EnableProxy:false}")
    private boolean enableProxy;

    private EventLoopGroup bossGroup;
    @Getter
    private EventLoopGroup workerGroup;
    private ChannelFuture serverFuture;

    /** Number of players currently in the play phase (used for the MOTD online count). */
    private final java.util.concurrent.atomic.AtomicInteger onlineCount = new java.util.concurrent.atomic.AtomicInteger();

    /** The players currently in the play phase, used to broadcast live TabList updates. */
    @Getter
    private final java.util.Set<UserConnection> onlineUsers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 正在连接但尚未进入 play 阶段的玩家（用于后端连接认证，登录时即可查到）。 */
    private final ConcurrentHashMap<UUID, Long> connectingUsers = new ConcurrentHashMap<>();
    private static final long CONNECTING_TTL_MILLIS = 60_000L;

    /** TabList 全量重推的最小间隔：同一间隔内的多次进出合并成一次刷新。 */
    private static final long TABLIST_REFRESH_INTERVAL_MILLIS = 1000L;

    /** 上一次真正执行 TabList 全量重推的时间戳。 */
    private final AtomicLong lastTabListRefreshAt = new AtomicLong();

    /** 是否已有一个合并后的延迟刷新在排队，避免每个进出都排一个任务。 */
    private final AtomicBoolean tabListRefreshQueued = new AtomicBoolean();

    public MinecraftProxy(ProxyProperties properties,
                          PlayerAuthService playerAuthService,
                          PingResponseProvider pingResponseProvider,
                          BackendServerManager backendServerManager,
                          EventBus eventBus,
                          TabListService tabListService,
                          PlayerMessageService playerMessageService,
                          PlayerStateService playerStateService,
                          // @Lazy breaks the construction cycle: PluginMessageService itself depends
                          // back on MinecraftProxy (for the online player set and event bus).
                          @Lazy PluginMessageService pluginMessageService,
                          moe.koseirin.nyanruaineo.Minecraft.command.CommandManager commandManager,
                          FirewallService firewallService,
                          PermissionService permissionService,
                          ProxyBanService proxyBanService,
                          @Lazy PlayerQueryService playerQueryService,
                          // @Lazy breaks the construction cycle: both fallback services depend back
                          // on MinecraftProxy (they are reached from the Netty handlers via
                          // proxy.getPlayerTransferService() / proxy.getPlayerKickService()).
                          @Lazy PlayerTransferService playerTransferService,
                          @Lazy PlayerKickService playerKickService) {
        this.properties = properties;
        this.playerAuthService = playerAuthService;
        this.pingResponseProvider = pingResponseProvider;
        this.backendServerManager = backendServerManager;
        this.eventBus = eventBus;
        this.tabListService = tabListService;
        this.playerMessageService = playerMessageService;
        this.playerStateService = playerStateService;
        this.pluginMessageService = pluginMessageService;
        this.commandManager = commandManager;
        this.firewallService = firewallService;
        this.permissionService = permissionService;
        this.proxyBanService = proxyBanService;
        this.playerQueryService = playerQueryService;
        this.playerTransferService = playerTransferService;
        this.playerKickService = playerKickService;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        if (!enableProxy) {
            log.info("Minecraft proxy is disabled");
            return;
        }

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        int cpuCores = Runtime.getRuntime().availableProcessors();
        workerGroup = new MultiThreadIoEventLoopGroup(cpuCores * 2, NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Firewall first: reject banned / flood connections before any protocol work.
                        ch.pipeline().addFirst("firewall", new FirewallHandler(firewallService));
                        HandlerBoss boss = PipelineUtils.initFrontendPipeline(ch.pipeline());
                        boss.setHandler(new InitialHandler(MinecraftProxy.this, ch));
                    }
                });

        serverFuture = bootstrap.bind(properties.getPort()).sync();
        log.info("Minecraft proxy started on port {}", properties.getPort());
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping Minecraft proxy...");
        if (serverFuture != null) {
            serverFuture.channel().close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("Minecraft proxy stopped.");
    }

    /** Called once a player reached the play phase. */
    public void playerJoined(UserConnection user) {
        connectingUsers.remove(user.getUuid());
        onlineUsers.add(user);
        onlineCount.incrementAndGet();
        refreshTabList();
    }

    /** Called once a play-phase player disconnects. */
    public void playerLeft(UserConnection user) {
        onlineUsers.remove(user);
        onlineCount.updateAndGet(value -> Math.max(0, value - 1));
        refreshTabList();
    }

    /** The current number of online (play-phase) players. */
    public int getOnlineCount() {
        return onlineCount.get();
    }

    /** 标记一个正在连接后端（尚未进入 play 阶段）的玩家，用于后端连接认证。 */
    public void markConnecting(UUID uuid) {
        if (uuid != null) {
            connectingUsers.put(uuid, System.currentTimeMillis() + CONNECTING_TTL_MILLIS);
        }
    }

    /** 该 UUID 是否当前被代理转发中（正在连接或已在 play 阶段）。 */
    public boolean isUuidKnown(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Long expire = connectingUsers.get(uuid);
        if (expire != null) {
            if (expire > System.currentTimeMillis()) {
                return true;
            }
            connectingUsers.remove(uuid);
        }
        for (UserConnection user : onlineUsers) {
            if (uuid.equals(user.getUuid())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Broadcasts a chat message to every connected player (each in their own version's chat
     * format) and returns the number of recipients.
     */
    public int broadcast(String text) {
        return playerMessageService.broadcast(onlineUsers, text);
    }

    /**
     * Re-pushes the TabList header/footer to every connected player whenever the online count
     * changed, so placeholders like {@code %online%} stay live for everyone.
     * <p>
     * 这个操作是 O(在线人数) 的，并且会对每个玩家各做一次 writeAndFlush。如果每次进人都立即全量
     * 重推，那么集中重连（后端重启、玩家集体掉线重连）时会退化成 O(n²)：n 次进出 × n 名玩家，
     * 还全部压在触发者的那条 Netty 事件循环线程上。因此这里把它合并成「最多每
     * {@value #TABLIST_REFRESH_INTERVAL_MILLIS} 毫秒刷新一次」：调用方只负责标脏，
     * 真正的工作交给 worker 事件循环延迟执行（{@code %online%} 这类占位符最多延迟这么多）。
     * <p>
     * TabList 功能未启用时直接返回，连遍历都省掉。
     */
    private void refreshTabList() {
        if (!tabListService.isEnabled()) {
            return;
        }
        if (workerGroup == null || workerGroup.isShuttingDown()) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = lastTabListRefreshAt.get();
        if (now - last >= TABLIST_REFRESH_INTERVAL_MILLIS) {
            lastTabListRefreshAt.set(now);
            pushTabList();
            return;
        }
        // 距上次刷新不足一个间隔：合并成一次延迟刷新（已经排队了就什么都不做）。
        if (tabListRefreshQueued.compareAndSet(false, true)) {
            long delay = TABLIST_REFRESH_INTERVAL_MILLIS - (now - last);
            try {
                workerGroup.schedule(() -> {
                    tabListRefreshQueued.set(false);
                    lastTabListRefreshAt.set(System.currentTimeMillis());
                    pushTabList();
                }, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // 正在关服：丢掉这次刷新即可。
                tabListRefreshQueued.set(false);
            }
        }
    }

    /** 真正遍历所有在线玩家并重推 TabList 头部/底部的那一步。 */
    private void pushTabList() {
        int count = onlineCount.get();
        for (UserConnection player : onlineUsers) {
            try {
                if (player.getChannel() == null || !player.getChannel().isActive()) {
                    onlineUsers.remove(player);
                    continue;
                }
                TabListHeaderFooter header = tabListService.buildHeaderFooter(player, count);
                if (header != null) {
                    player.sendPacket(header);
                }
            } catch (Exception e) {
                // 限流：一次刷新会对每个在线玩家各打一条，出问题时很容易刷屏。
                String gate = LogThrottle.acquire("tablist-refresh-failed", 10_000L);
                if (gate != null) {
                    log.warn("Failed to refresh the TabList for {}{}", player.getUsername(), gate, e);
                }
            }
        }
    }


    public PlayerQueryService.PlayerInfo findPlayerByUUID(String uuid) {
        return playerQueryService.findPlayerByUUID(uuid);
    }
}
