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
import moe.koseirin.nyanruaineo.services.PermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
                          @Lazy PlayerQueryService playerQueryService) {
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
     */
    private void refreshTabList() {
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
                log.warn("Failed to refresh the TabList for {}", player.getUsername(), e);
            }
        }
    }


    public PlayerQueryService.PlayerInfo findPlayerByUUID(String uuid) {
        return playerQueryService.findPlayerByUUID(uuid);
    }
}
