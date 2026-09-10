package moe.koseirin.nyanruaineo.Minecraft.handler;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerDisconnectEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerJoinEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerLoginEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.ProxyPingEvent;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeConstants;
import moe.koseirin.nyanruaineo.Minecraft.netty.HandlerBoss;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketEncoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PipelineUtils;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EncryptionRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EncryptionResponse;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ForgeHandshake;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Handshake;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Kick;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginSuccess;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PingPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StatusRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StatusResponse;
import moe.koseirin.nyanruaineo.entity.BanUserList;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 处理传入客户端连接的握手、状态和登录阶段。
 * 登录完成后，它会将通道交给 {@link UpstreamBridge}，
 * 并将后端连接的建立委托给 {@link ServerConnector}。
 */
@Slf4j
public class InitialHandler extends ChannelInboundHandlerAdapter {

    private final MinecraftProxy proxy;

    private final Channel channel;

    private Protocol protocol = Protocol.HANDSHAKE;
    private int protocolVersion = -1;

    private String username;
    private UUID uuid;
    private UUID loginUuid;
    private String requestedServer;
    /** Extra data appended to the handshake host after the first NUL byte (e.g. the FML token). */
    private String extraDataInHandshake = "";
    private List<LoginSuccess.Property> properties = Collections.emptyList();
    private byte[] verifyToken;
    private KeyPair keyPair;
    private ServerConnection server;
    private UserConnection user;
    private boolean loginInProgress;

    public InitialHandler(MinecraftProxy proxy, Channel channel) {
        this.proxy = proxy;
        this.channel = channel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Handshake handshake) {
            handleHandshake(handshake);
        } else if (msg instanceof StatusRequest) {
            handleStatusRequest();
        } else if (msg instanceof PingPacket ping) {
            handlePing(ping);
        } else if (msg instanceof ForgeHandshake forgeHandshake) {
            handleForgeHandshake(forgeHandshake);
        } else if (msg instanceof LoginRequest loginRequest) {
            handleLoginRequest(loginRequest);
        } else if (msg instanceof EncryptionResponse response) {
            handleEncryptionResponse(response);
        } else if (msg instanceof ByteBuf buf) {
            // Unknown packet in a pre-play state: relay it to the backend if one is connected
            // (login plugin messages, cookies, etc.), otherwise drop it.
            ServerConnection backend = user == null ? null : user.getServer();
            if (backend != null && backend.getChannel().isActive()) {
                backend.getChannel().writeAndFlush(buf, backend.getChannel().voidPromise());
            } else {
                ReferenceCountUtil.release(msg);
            }
        } else {
            ReferenceCountUtil.release(msg);
        }
    }

    private void handleHandshake(Handshake handshake) {
        this.protocolVersion = handshake.getProtocolVersion();
        getPacketDecoder().setProtocolVersion(protocolVersion);
        getPacketEncoder().setProtocolVersion(protocolVersion);

        // FML 1.8+ appends a "\0FML\0" token (and possibly other NUL-delimited data) to the host.
        // Strip it for routing, and remember it so it can be restored on the backend handshake.
        String host = handshake.getHost();
        if (host.contains("\0")) {
            String[] split = host.split("\0", 2);
            this.requestedServer = split[0];
            this.extraDataInHandshake = "\0" + split[1];
        } else {
            this.requestedServer = host;
        }

        log.debug("Handshake from client: protocol={}, address={}, nextState={}, extraData={}",
                protocolVersion, requestedServer, handshake.getRequestedProtocol(),
                extraDataInHandshake.isEmpty() ? "-" : extraDataInHandshake);

        if (handshake.getRequestedProtocol() == 1) {
            setProtocol(Protocol.STATUS);
        } else if (handshake.getRequestedProtocol() == 2 || handshake.getRequestedProtocol() == 3) {
            // 2 = login, 3 = transfer (1.20.5+) — both proceed through the login phase.
            setProtocol(Protocol.LOGIN);
        } else {
            channel.close();
        }
    }

    private void handleStatusRequest() {
        proxy.getEventBus().postAsync(new ProxyPingEvent(protocolVersion, remoteIp()));
        String json = proxy.getPingResponseProvider().getPingJson(protocolVersion, proxy.getOnlineCount());
        channel.writeAndFlush(new StatusResponse(json));
    }

    private void handlePing(PingPacket ping) {
        channel.writeAndFlush(new PingPacket(ping.getPayload()));
    }

    /**
     * Terminates the pre-1.8 Forge (FML 1.7) handshake at the proxy with an empty mod list.
     */
    private void handleForgeHandshake(ForgeHandshake forgeHandshake) {
        if (!ForgeHandshake.FML_HANDSHAKE_CHANNEL.equals(forgeHandshake.getChannel())) {
            return;
        }
        byte[] data = forgeHandshake.getData();
        if (data == null || data.length == 0) {
            return;
        }
        byte discriminator = data[0];
        if (discriminator == 0) {
            // SERVERHELLO: echo it back.
            channel.writeAndFlush(new ForgeHandshake(ForgeHandshake.FML_HANDSHAKE_CHANNEL, new byte[]{0x00, 0x01}));
        } else if (discriminator == 2) {
            // Client MODLIST: respond with an empty server mod list.
            channel.writeAndFlush(new ForgeHandshake(ForgeHandshake.FML_HANDSHAKE_CHANNEL, new byte[]{0x02, 0x00}));
        } else if (discriminator == -1) {
            // ACK: respond with ACK.
            channel.writeAndFlush(new ForgeHandshake(ForgeHandshake.FML_HANDSHAKE_CHANNEL, new byte[]{(byte) 0xFF, 0x00}));
        }
    }

    private void handleLoginRequest(LoginRequest loginRequest) {
        // Firewall: rate-limit login attempts before any encryption/auth, so bots cannot hammer
        // the Yggdrasil/Mojang session server (the "Mojang session returned no profile" flood).
        if (!proxy.getFirewallService().tryLogin(remoteAddress())) {
            log.warn("Firewall: rejected login attempt from {} ({})", remoteIp(), loginRequest.getUsername());
            channel.close();
//            channel.writeAndFlush(new Kick("{\"text\":\"登录过于频繁，请稍后再试喵~\",\"color\":\"red\"}"))
//                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (loginInProgress) {
            log.warn("Duplicate LoginStart from {} (ignored)", loginRequest.getUsername());
            return;
        }
        loginInProgress = true;
        this.username = loginRequest.getUsername();
        this.loginUuid = loginRequest.getUuid();
        log.debug("{}: LoginStart protocol={} loginUuid={}, online-mode={}",
                username, protocolVersion, loginUuid, proxy.getProperties().isOnlineMode());

        if (proxy.getProperties().isOnlineMode()) {
            startEncryption();
        } else {
            finishLogin(offlineUuid(username), Collections.emptyList());
        }
    }

    private void startEncryption() {
        try {
            this.keyPair = generateKeyPair();
            this.verifyToken = generateVerifyToken();
            EncryptionRequest request = new EncryptionRequest("", keyPair.getPublic().getEncoded(), verifyToken);
            channel.writeAndFlush(request);
            log.debug("{}: EncryptionRequest sent ({} bit RSA)", username, keyPair.getPublic().getEncoded().length * 8);
        } catch (Exception e) {
            log.error("Failed to start encryption for {}", username, e);
            channel.close();
        }
    }

    private void handleEncryptionResponse(EncryptionResponse response) {
        try {
            byte[] sharedSecret = rsaDecrypt(response.getSharedSecret(), keyPair.getPrivate());
            // 1.19-1.19.2 clients may send a salt+signature instead of the verify token.
            if (response.getVerifyToken() != null) {
                byte[] token = rsaDecrypt(response.getVerifyToken(), keyPair.getPrivate());
                if (!Arrays.equals(token, verifyToken)) {
                    throw new IllegalStateException("verify token mismatch");
                }
            }
            log.debug("{}: encryption established (protocol {}), starting auth", username, protocolVersion);

            enableEncryption(sharedSecret);

            // Compatible auth: the internal Yggdrasil sessionserver is checked first (no HTTP),
            // falling back to the Mojang session server — the login source is auto-detected.
            long authStart = System.currentTimeMillis();
            proxy.getPlayerAuthService()
                    .authenticate(username, sharedSecret, keyPair.getPublic().getEncoded())
                    .thenAccept(profile -> channel.eventLoop().execute(() -> {
                        if (profile == null) {
                            // Invalid / expired session: answer the client with a kick instead of
                            // silently closing the connection.
                            proxy.getFirewallService().recordLoginFailure(remoteAddress());
                            channel.writeAndFlush(new Kick(
                                    "{\"text\":\"无效的会话喵～ (请重启游戏，目前仅支持本服外置和Mojang混合登陆喵!)\",\"color\":\"red\"}"))
                                    .addListener(ChannelFutureListener.CLOSE);
                            return;
                        }
                        log.debug("{}: auth ok ({} ms) uuid={}", username,
                                System.currentTimeMillis() - authStart, profile.uuid());
                        proxy.getFirewallService().recordLoginSuccess(remoteAddress());
                        finishLogin(profile.uuid(), profile.properties());
                    }))
                    .exceptionally(throwable -> {
                        log.error("Authentication failed for {} after {} ms: {}", username,
                                System.currentTimeMillis() - authStart,
                                throwable.getCause() != null ? throwable.getCause().getMessage()
                                        : throwable.getMessage());
                        proxy.getFirewallService().recordLoginFailure(remoteAddress());
                        channel.eventLoop().execute(() -> channel.writeAndFlush(new Kick(
                                        "{\"text\":\"无效的会话喵～ (请重启游戏，目前仅支持本服外置和Mojang混合登陆喵!)\",\"color\":\"red\"}"))
                                .addListener(ChannelFutureListener.CLOSE));
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to process encryption response for {}", username, e);
            channel.close();
        }
    }

    private void enableEncryption(byte[] sharedSecret) throws Exception {
        SecretKeySpec secretKey = new SecretKeySpec(sharedSecret, "AES");
        Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(sharedSecret));
        Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(sharedSecret));
        PipelineUtils.enableEncryption(channel.pipeline(), encryptCipher, decryptCipher);
    }

    private void finishLogin(UUID authenticatedUuid, List<LoginSuccess.Property> authenticatedProperties) {
        this.uuid = authenticatedUuid;
        this.properties = authenticatedProperties;
        if (!proxy.getProperties().isOnlineMode() && proxy.findPlayerByUUID(String.valueOf(authenticatedUuid))!= null ){
            channel.writeAndFlush(new Kick("You are already connected to this proxy!"));
            channel.close();
        }
        checkBanThenConnect(authenticatedUuid, authenticatedProperties);
    }

    /**
     * 封禁查询<b>绝不可以</b>在 Netty 事件循环上同步执行：{@link
     * moe.koseirin.nyanruaineo.Minecraft.service.ProxyBanService#findGameBan} 是阻塞的 JDBC 查询
     * （一次登录最多三条 SQL）。在事件循环里查库会让该 worker 线程上的所有玩家一起卡住，
     * 并发登录时所有事件循环都会停在数据库 I/O 上，并一起争抢 Hikari 连接池。
     * 因此这里查到工作线程上执行，结果再回到事件循环继续登录。
     * <p>
     * 查询期间先暂停读取：这与过去「事件循环被阻塞、期间无法处理其它数据包」的时序保持一致，
     * 免得登录尚未建立时又收到客户端的后续数据包。
     */
    private void checkBanThenConnect(UUID authenticatedUuid, List<LoginSuccess.Property> authenticatedProperties) {
        channel.config().setAutoRead(false);
        proxy.getProxyBanService().findGameBanAsync(authenticatedUuid)
                .whenComplete((ban, error) -> channel.eventLoop().execute(() -> {
                    if (error != null) {
                        // 查询本身在 ProxyBanService 内部已经 fail-open，这里只是兜底。
                        log.warn("Game ban check failed for {}, continuing the login: {}",
                                username, error.toString());
                    }
                    if (ban != null) {
                        log.info("{} ({}) blocked by game ban {} ({})", username, authenticatedUuid,
                                ban.getBanID(), ban.getReason());
                        channel.writeAndFlush(
                                        new Kick(proxy.getProxyBanService().buildBanKickJson(ban, username)))
                                .addListener(ChannelFutureListener.CLOSE);
                        return;
                    }
                    startBackendConnection(authenticatedUuid, authenticatedProperties);
                }));
    }

    /** 封禁检查通过之后的登录收尾：建立 UserConnection 并连接后端。 */
    private void startBackendConnection(UUID authenticatedUuid,
                                        List<LoginSuccess.Property> authenticatedProperties) {
        this.user = new UserConnection(channel);
        user.setUsername(username);
        user.setUuid(authenticatedUuid);
        user.setLoginUuid(loginUuid);
        user.setProperties(authenticatedProperties);
        user.setProtocolVersion(protocolVersion);
        user.setRequestedServer(requestedServer);
        user.setExtraDataInHandshake(extraDataInHandshake);
        // The FML 1.8 token in the handshake identifies a Forge client (BungeeCord UserConnection).
        user.getForgeClientHandler().setFmlTokenInHandshake(
                extraDataInHandshake.contains(ForgeConstants.FML_HANDSHAKE_TOKEN));

        proxy.getEventBus().postAsync(
                new PlayerLoginEvent(username, authenticatedUuid, protocolVersion, requestedServer, remoteIp()));

        // Pause reading until the backend is ready to relay.
        channel.config().setAutoRead(false);
        log.debug("{}: login complete (uuid={}), connecting to backend (requested {})",
                username, authenticatedUuid, requestedServer);
        new ServerConnector(proxy, user, this).connect();
    }

    /**
     * 由 {@link ServerConnector} 在代理端向客户端发送 Login Success 数据包后调用（仅限 pre-1.20.2 版本）。
     * 从客户端自身视角来看，它已经处于游戏状态了——Forge 后端会在 Login Success 之后、JoinGame 之前
     * 立即发送 FML 握手——因此前端编解码器切换至 GAME 阶段，并且现在安装游戏阶段的转发桥接器，
     */
    public void onLoginSuccess(ServerConnection server) {
        this.server = server;

        setProtocol(Protocol.GAME);

        HandlerBoss boss = channel.pipeline().get(HandlerBoss.class);
        boss.setHandler(new UpstreamBridge(proxy, server));
    }

    /**
     * 由 {@link ServerConnector} 在后端发送的 JoinGame 数据包被转发后调用：
     * 将前端切换至游戏阶段（对于 1.20.2+ 客户端，这是切换发生的时机；
     * 对于旧版本客户端，{@link #onLoginSuccess} 已经完成了切换），
     * 恢复对客户端的读取，并将该玩家注册到代理端。
     */
    public void onServerConnected(ServerConnection server) {
        this.server = server;

        // The player reached the backend: reset its IP's firewall counters so later reconnects
        // start from a clean slate.
        proxy.getFirewallService().resetIp(remoteAddress());

        setProtocol(Protocol.GAME);

        HandlerBoss boss = channel.pipeline().get(HandlerBoss.class);
        boss.setHandler(new UpstreamBridge(proxy, server));

        channel.config().setAutoRead(true);

        // 注册该玩家，并向所有已连接的玩家广播 TabList 的头部/底部信息（包含实时的 %online% 占位符）
        // —— 包括刚刚完成注册的这名玩家自身，因为它的前端编解码器刚刚切换到了 GAME 阶段。
        // 此操作同样适用于那些本身从不发送头部/底部信息的后端服务器。
        proxy.playerJoined(user);
        proxy.getEventBus().postAsync(
                new PlayerJoinEvent(username, uuid, protocolVersion, server.getHost(), server.getPort()));
        logConnectedToBackend(server);
    }

    /**
     * 仅限 1.20.2 及以上版本：在代理端发送 Login Success 后调用。
     * 此时客户端处于配置阶段（而非游戏阶段），因此出站编解码器切换至 CONFIGURATION，
     * 安装游戏阶段的转发桥接器并恢复读取——客户端必须能够发送其
     * {@code LoginAcknowledged} 及配置阶段握手。
     * 入站编解码器会保持在 LOGIN 状态，直到 {@code LoginAcknowledged} 推进它
     */
    public void enterConfiguration(ServerConnection server) {
        this.server = server;

        getPacketEncoder().setProtocol(Protocol.CONFIGURATION);

        HandlerBoss boss = channel.pipeline().get(HandlerBoss.class);
        boss.setHandler(new UpstreamBridge(proxy, server));

        channel.config().setAutoRead(true);
        log.debug("{}: front-end -> CONFIGURATION (encode), UpstreamBridge installed, reads resumed",
                username);
    }

    /**
     * 仅限 1.20.2 及以上版本：在配置阶段完成后注册该玩家（后端发送了
     * {@code FinishConfiguration}，紧接着将发送 Join Game），
     */
    public void onConfigurationDone(ServerConnection server) {
        this.server = server;
        // The player reached the backend: reset its IP's firewall counters so later reconnects
        // start from a clean slate.
        proxy.getFirewallService().resetIp(remoteAddress());
        proxy.playerJoined(user);
        proxy.getEventBus().postAsync(
                new PlayerJoinEvent(username, uuid, protocolVersion, server.getHost(), server.getPort()));
        logConnectedToBackend(server);
    }
    private void logConnectedToBackend(ServerConnection server) {
        log.info("{} ({}) connected to backend {}:{} (protocol {})",
                username, uuid, server.getHost(), server.getPort(), protocolVersion);
    }

    private String remoteIp() {
        if (channel.remoteAddress() instanceof InetSocketAddress remote) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }

    /** 客户端的远程地址（用于防火墙限流），无地址时返回 {@code null}。 */
    private InetSocketAddress remoteAddress() {
        return channel.remoteAddress() instanceof InetSocketAddress remote ? remote : null;
    }

    private void setProtocol(Protocol newProtocol) {
        this.protocol = newProtocol;
        getPacketDecoder().setProtocol(newProtocol);
        getPacketEncoder().setProtocol(newProtocol);
    }

    private PacketDecoder getPacketDecoder() {
        return channel.pipeline().get(PacketDecoder.class);
    }

    private PacketEncoder getPacketEncoder() {
        return channel.pipeline().get(PacketEncoder.class);
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(1024);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate RSA keypair", e);
        }
    }

    private static byte[] generateVerifyToken() {
        byte[] token = new byte[4];
        new SecureRandom().nextBytes(token);
        return token;
    }

    private static byte[] rsaDecrypt(byte[] data, PrivateKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (server != null && !server.isClosed()) {
            server.close();
        }
        proxy.getEventBus().postAsync(new PlayerDisconnectEvent(username, uuid));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("InitialHandler exception: {}", cause.getMessage());
        ctx.close();
    }
}
