package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.handler.ServerConnector;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Encapsulates moving a player from one backend (sub) server to another, mirroring BungeeCord's
 * server-connect flow. The target's online status is checked first (off the Netty event loop so a
 * probe against a dead backend never stalls other players), and an error is sent back when the
 * server is offline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlayerTransferService {

    private static final String FALLBACK_NOTICE = "§c意外与服务器断开连接，正在将你拉回大厅喵…";

    private static final String FALLBACK_FAILED_REASON = "与服务器的连接已断开，且无法返回大厅喵";

    private final MinecraftProxy proxy;
    private final BackendServerManager backendServerManager;

    /**
     * Checks the target server is online and transfers the player; when it is offline the
     * {@code reply} consumer receives the error message instead.
     */
    public void transferIfOnline(UserConnection user, BackendServer target, Consumer<String> reply) {
        backendServerManager.isOnlineAsync(target).thenAccept(online -> {
            if (!online) {
                reply.accept("§cServer " + target.getName() + " is offline!");
                return;
            }
            // Only report success when a transfer was actually started: a backend may deliver the
            // same Connect/ConnectOther once per online player connection, so the duplicate must
            // not emit a second "前往..." reply.
            if (transfer(user, target)) {
                reply.accept("§a前往 " + target.getUid() + "...");
            }
        });
    }

    /**
     * The actual server switch: pause the client, bump the server generation (so the old
     * backend's close cannot tear the client down), close the old backend, then connect to the
     * new one.
     *
     * @return {@code true} when a switch was started; {@code false} when it was skipped (player
     *         already disconnected, or a switch to some backend is already in progress).
     */
    public boolean transfer(UserConnection user, BackendServer target) {
        if (!beginTransfer(user, target)) {
            return false;
        }
        doTransfer(user, target);
        return true;
    }

    /**
     * 后端把玩家连接关闭（崩溃 / 停服 / 网络断开）时的兜底：强制把玩家送回大厅。
     * <p>
     * 大厅取配置里的默认服务器（{@code default_server}，未配置时取最高优先级服务器）。
     * 以下情况直接踢出玩家：没有可用大厅、大厅就是刚掉线的那台、或大厅连不上 / 登录被拒
     * （后两者由 {@link ServerConnector} 的处理链转成踢出界面）。踢出界面复用
     * {@code proxy.kick-message} 的可配置模板。
     * <p>
     * 幂等：后端关闭会在 {@code ServerConnector} 的 closeFuture 与
     * {@code DownstreamBridge.channelInactive} 两处各通知一次，用掉线的那条通道当令牌，
     * 只有第一个能真正开始回退。
     *
     * @param droppedChannel 掉线的后端通道，用于识别"同一次关闭"的重复通知
     * @return {@code true} 表示本次调用已接管这个客户端（开始回退、已踢出，或同一次关闭的
     *         另一个通知已经接管）；{@code false} 表示这并非"玩家当前所在后端掉线"（还在登录
     *         阶段、后端尚未被采用，或有正常切服在进行），需要调用方按原有方式关掉客户端
     */
    public boolean fallbackToLobby(UserConnection user, Channel droppedChannel) {
        if (user == null || user.getChannel() == null || !user.getChannel().isActive()) {
            return true; // 客户端已经断开，没有可做的了
        }

        ServerConnection dead;
        synchronized (user) {
            if (droppedChannel != null && droppedChannel == user.getHandledDropChannel()) {
                // 同一次后端关闭的另一个通知已经接管了这个客户端。
                return true;
            }
            dead = user.getServer();
            if (dead == null || user.isSwitchingServer() || !proxy.getOnlineUsers().contains(user)) {
                // 不是"玩家当前所在后端掉线"：还在登录阶段、后端尚未被采用，或已有一次正常
                // 切服在进行 —— 那些流程自己负责收尾，这里不要抢。
                return false;
            }
            user.setHandledDropChannel(droppedChannel);
            // The switch flag must be set so UpstreamBridge drops the client's stale GAME frames
            // until the lobby's JoinGame arrives (doTransfer relies on it).
            user.setSwitchingServer(true);
        }

        BackendServer lobby = backendServerManager.select(null);
        if (lobby == null || isSameServer(lobby, dead)) {
            log.warn("Backend {} dropped {} and there is no lobby to fall back to (lobby={}); kicking",
                    describe(dead), user.getUsername(), lobby == null ? "none" : lobby.getName());
            proxy.getPlayerKickService().kick(user, FALLBACK_FAILED_REASON, null);
            return true;
        }

        log.info("Backend {} dropped {}; falling back to lobby {} ({}:{})", describe(dead),
                user.getUsername(), lobby.getName(), lobby.getHost(), lobby.getPort());
        proxy.getPlayerMessageService().sendMessage(user, FALLBACK_NOTICE);
        doTransfer(user, lobby);
        return true;
    }

    /**
     * 占位一次切换：玩家已离线或已有切换在进行时返回 {@code false}。
     * 与 {@link #doTransfer} 拆开，好让 {@link #fallbackToLobby} 复用同一套占地逻辑。
     */
    private boolean beginTransfer(UserConnection user, BackendServer target) {
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            log.info("Player {} disconnected before the transfer to {} could start",
                    user.getUsername(), target.getName());
            return false;
        }

        // Idempotent check-and-set: with several players on one backend, the backend sends the
        // same Connect/ConnectOther plugin message through every player connection, and each
        // DownstreamBridge runs this branch. Only the first call may start the switch; the others
        // are dropped so they don't spawn a second racing ServerConnector.
        synchronized (user) {
            if (user.isSwitchingServer()) {
                log.debug("Player {} is already switching; ignoring duplicate transfer to {}",
                        user.getUsername(), target.getName());
                return false;
            }
            user.setSwitchingServer(true);
        }
        return true;
    }

    /** 真正执行切换；调用前必须已经由 {@link #beginTransfer} 占位。 */
    private void doTransfer(UserConnection user, BackendServer target) {
//        log.info("Transferring {} to {} ({}:{})", user.getUsername(), target.getName(),
//                target.getHost(), target.getPort());

        // Leaving a Forge server: reset the client's FML handshake so the next backend starts
        // from a clean HELLO state (BungeeCord ServerConnector.handle(LoginSuccess)).
        ServerConnection old = user.getServer();
        if (old != null && old.isForgeServer() && user.getForgeClientHandler().isHandshakeComplete()) {
            user.getForgeClientHandler().resetHandshake();
        }

        // The switch flag is now set (above), so UpstreamBridge discards the client's stale GAME
        // frames until the new backend's JoinGame arrives (BungeeCord shouldHandle parity).
        user.getChannel().config().setAutoRead(false);
        user.nextServerGeneration();
        user.setServer(null);
        if (old != null && !old.isClosed()) {
            old.close();
        }
        new ServerConnector(proxy, user, target).connect();
    }

    /** 判断某个配置项是否指向玩家当前（或刚掉线的）后端连接。 */
    private static boolean isSameServer(BackendServer server, ServerConnection connection) {
        if (connection == null || server.getHost() == null) {
            return false;
        }
        return server.getHost().equalsIgnoreCase(connection.getHost())
                && server.getPort() == connection.getPort();
    }

    private static String describe(ServerConnection connection) {
        return connection == null ? "none" : connection.getHost() + ":" + connection.getPort();
    }
}
