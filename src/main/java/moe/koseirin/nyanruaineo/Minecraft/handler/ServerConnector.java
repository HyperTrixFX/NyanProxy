package moe.koseirin.nyanruaineo.Minecraft.handler;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeConstants;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeServerHandler;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeUtils;
import moe.koseirin.nyanruaineo.Minecraft.netty.HandlerBoss;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketCompressor;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecompressor;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketEncoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PipelineUtils;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolConstants;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.*;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerStateService;
import moe.koseirin.nyanruaineo.Minecraft.util.LogThrottle;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 将一个已登录的客户端连接到后端服务器并执行登录握手。
 * 成功后，会在两端安装游戏阶段的桥接器。
 */
@Slf4j
public class ServerConnector {

    private final MinecraftProxy proxy;
    private final UserConnection user;
    private final InitialHandler initialHandler;
    private final boolean serverSwitch;
    private final BackendServer backend;
    private final PlayerStateService playerState;

    /** The Forge handshake handler for the backend currently being connected (created on connect). */
    private ForgeServerHandler handshakeHandler;

    public ServerConnector(MinecraftProxy proxy, UserConnection user, InitialHandler initialHandler) {
        this(proxy, user, initialHandler, null, false);
    }

    /**
     * Creates a connector for a cross-server switch to a specific backend. The Login Success is not
     * sent to the client again (it is already in the play phase).
     */
    public ServerConnector(MinecraftProxy proxy, UserConnection user, BackendServer target) {
        this(proxy, user, null, target, true);
    }

    private ServerConnector(MinecraftProxy proxy, UserConnection user, InitialHandler initialHandler,
                            BackendServer target, boolean serverSwitch) {
        this.proxy = proxy;
        this.user = user;
        this.initialHandler = initialHandler;
        this.serverSwitch = serverSwitch;
        this.backend = target != null ? target : proxy.getBackendServerManager().select(user.getRequestedServer());
        this.playerState = proxy.getPlayerStateService();
    }

    public void connect() {
        if (backend == null) {
            log.error("No backend servers configured for {} (check proxy.backend.servers)", user.getUsername());
            disconnectClient("{\"text\":\"系统目前没有配置着陆点，传送失败喵～\"}");
            return;
        }
        String host = backend.getHost();
        int port = backend.getPort();
        log.debug("{}: connecting to backend {} ({}:{})", user.getUsername(), backend.getName(), host, port);

        // 后端连接认证：首次连接时先把该玩家标记为「正在连接」，后端插件可在登录时通过 v7 查询到。
        if (!serverSwitch) {
            proxy.markConnecting(user.getUuid());
        }

        Bootstrap bootstrap = new Bootstrap()
                .group(proxy.getWorkerGroup())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        PipelineUtils.initBackendPipeline(pipeline);
                        int generation = user.getServerGeneration();
                        ch.closeFuture().addListener(future -> {
                            log.debug("Backend channel closed for {}", user.getUsername());
                            // Only act when this backend is still the current one (a server switch
                            // bumps the generation, so the old backend's close is ignored).
                            if (user.getServerGeneration() == generation && user.getChannel().isActive()) {
                                // The current backend dropped the player: pull them back to the
                                // lobby — or kick them when there is nowhere to go — instead of
                                // silently closing the client.
                                if (!proxy.getPlayerTransferService().fallbackToLobby(user, ch)) {
                                    user.getChannel().close();
                                }
                            }
                        });
                    }
                });

        bootstrap.connect(host, port).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                // 限流：后端子服挂掉时，每个玩家的每次重连都会走到这里，很容易刷屏。
                String gate = LogThrottle.acquire("backend-connect-failed", 10_000L);
                if (gate != null) {
                    log.error("Could not connect to backend {}:{} for {},cause: {}{}",
                            host, port, user.getUsername(), future.cause().getMessage(), gate);
                }
                disconnectClient("{\"text\":\"代号325空降失败了喵～因为系统找不到着陆点喵～\"}");
                return;
            }
            log.debug("{}: backend {}:{} connected", user.getUsername(), host, port);

            Channel backendChannel = future.channel();
            ServerConnection server = new ServerConnection(backendChannel, host, port);
            server.setUser(user);
            // On a server switch the current server stays null until the new backend's JoinGame is
            // relayed, so the old bridge drops its stale packets and the client's own packets are
            // not forwarded into the still-logging-in backend.
            if (!serverSwitch) {
                user.setServer(server);
            }
            // The Forge server handler is created for every backend connect (BungeeCord
            // ServerConnector.connected); it is only exposed to the client handler once the
            // backend proves to be a Forge server.
            if (proxy.getProperties().isForgeSupport()) {
                handshakeHandler = new ForgeServerHandler(user, server);
            }

            PacketDecoder decoder = backendChannel.pipeline().get(PacketDecoder.class);
            PacketEncoder encoder = backendChannel.pipeline().get(PacketEncoder.class);
            decoder.setProtocolVersion(user.getProtocolVersion());
            encoder.setProtocolVersion(user.getProtocolVersion());

            // 1. Handshake with the LOGIN intent, encoded in the HANDSHAKE state.
            Handshake handshake = new Handshake(user.getProtocolVersion(), buildForwardedHost(host), port, 2);
            backendChannel.writeAndFlush(handshake);

            // 2. Switch to LOGIN state and send the login request.
            decoder.setProtocol(Protocol.LOGIN);
            encoder.setProtocol(Protocol.LOGIN);
            LoginRequest loginRequest = new LoginRequest(user.getUsername());
            loginRequest.setUuid(user.getLoginUuid());
            backendChannel.writeAndFlush(loginRequest);

            // 3. Wait for the backend to accept (or reject) the login.
            HandlerBoss boss = backendChannel.pipeline().get(HandlerBoss.class);
            boss.setHandler(new LoginListener(backendChannel, server));
        });
    }

    /**
     * Sends a client-bound disconnect and closes the channel once the message is flushed.
     */
    private void disconnectClient(String reason) {
        if (!serverSwitch) {
            // Still in the LOGIN phase: a client-bound Kick (0x00) can be written directly.
            user.getChannel().writeAndFlush(new Kick(reason)).addListener(ChannelFutureListener.CLOSE);
        } else {
            // Already in the play phase after a failed switch / lobby fallback: send the play-state
            // Disconnect packet so the player sees a real kick screen with the reason instead of a
            // silent "connection lost". The reason is a JSON chat component in both paths.
            proxy.getPlayerKickService().disconnectJson(user, reason);
        }
    }

    /**
     * Builds the Handshake host field, appending BungeeCord IP-forwarding data when enabled:
     * {@code host\0ip\0uuid\0propertiesJson}. When IP forwarding is disabled, the NUL-delimited
     * extra data stripped from the client's handshake (e.g. the FML 1.8+ token) is restored instead.
     */
    private String buildForwardedHost(String host) {
        if (proxy.getProperties().isIpForward()) {
            String ip = "127.0.0.1";
            if (user.getChannel().remoteAddress() instanceof InetSocketAddress remote) {
                ip = remote.getAddress().getHostAddress();
            }

            JSONArray properties = new JSONArray();
            for (LoginSuccess.Property property : user.getProperties()) {
                JSONObject prop = new JSONObject();
                prop.put("name", property.getName());
                prop.put("value", property.getValue());
                prop.put("signature", property.getSignature() == null ? "" : property.getSignature());
                properties.add(prop);
            }

            return host + "\0" + ip + "\0" + user.getUuid() + "\0" + properties.toJSONString();
        }

        String extra = user.getExtraDataInHandshake();
        return host + (extra == null ? "" : extra);
    }

    /**
     * Temporary backend handler that watches for the login result, then swaps in the play-phase
     * bridge.
     */
    private final class LoginListener extends ChannelInboundHandlerAdapter {

        private final Channel backendChannel;
        private final ServerConnection server;

        private LoginListener(Channel backendChannel, ServerConnection server) {
            this.backendChannel = backendChannel;
            this.server = server;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof LoginSuccess) {
                onLoginSuccess();
            } else if (msg instanceof JoinGame joinGame) {
                onJoinGame(joinGame);
            } else if (msg instanceof Kick kick) {
                log.warn("Login denied by backend for {},Reason: {}", user.getUsername(),kick.getReason());
                disconnectClient(kick.getReason());
            } else if (msg instanceof SetCompression setCompression) {
                enableCompression(setCompression.getThreshold());
            } else if (msg instanceof EncryptionRequest) {
                log.error("Backend {}:{} requested encryption (online mode). The backend must run in "
                        + "offline mode (online-mode=false) so the proxy can complete login.",
                        server.getHost(), server.getPort());
                disconnectClient("{\"text\":\"Backend server is misconfigured (online mode)\"}");
            } else if (msg instanceof PluginMessage pluginMessage) {
                handleLoginPluginMessage(pluginMessage);
            } else if (msg instanceof io.netty.buffer.ByteBuf buf) {
                // Unknown login-state packet from the backend (e.g. login plugin request): relay it
                // to the client verbatim.
                Channel userChannel = user.getChannel();
                if (userChannel.isActive()) {
                    userChannel.writeAndFlush(buf, userChannel.voidPromise());
                } else {
                    ReferenceCountUtil.release(msg);
                }
            } else {
                ReferenceCountUtil.release(msg);
            }
        }

        /**
         * Handles plugin messages arriving while the backend is still connecting (Forge servers
         * send their channel registration and the FML handshake right after Login Success, before
         * the JoinGame), mirroring BungeeCord's {@code ServerConnector.handle(PluginMessage)}.
         */
        private void handleLoginPluginMessage(PluginMessage pluginMessage) {
            if (proxy.getProperties().isForgeSupport()) {
                if (ForgeConstants.FML_REGISTER.equals(pluginMessage.getTag())) {
                    Set<String> channels = ForgeUtils.readRegisteredChannels(pluginMessage);
                    boolean isForgeServer = false;
                    for (String channel : channels) {
                        if (channel.equals(ForgeConstants.FML_HANDSHAKE_TAG)) {
                            // If we have a completed handshake and the new backend asks to register
                            // FML|HS, the handshake was already reset by the switch (see
                            // PlayerTransferService); this covers the first connection case too.
                            if (user.getServer() != null && user.getForgeClientHandler().isHandshakeComplete()) {
                                user.getForgeClientHandler().resetHandshake();
                            }
                            isForgeServer = true;
                            break;
                        }
                    }
                    if (isForgeServer && handshakeHandler != null && !handshakeHandler.isServerForge()) {
                        // We now set the server-side handshake handler for the client to this.
                        handshakeHandler.setServerAsForgeServer();
                        server.setServerAsForgeServer();
                        user.setForgeServerHandler(handshakeHandler);
                    }
                }

                if (ForgeConstants.FML_HANDSHAKE_TAG.equals(pluginMessage.getTag())
                        || ForgeConstants.FORGE_REGISTER.equals(pluginMessage.getTag())) {
                    if (handshakeHandler != null) {
                        handshakeHandler.handle(pluginMessage);
                    }
                    // The handler sends the message itself, so don't send it here.
                    return;
                }
            }

            // Forward everything else to the client, especially with Forge as stuff might break.
            // This includes any REGISTER messages we intercepted earlier.
            user.sendPacket(pluginMessage);
        }

        private void enableCompression(int threshold) {
            if (backendChannel.pipeline().get(ProtocolConstants.COMPRESSION_ENCODER) != null) {
                return;
            }
            // Inbound: frame-decoder -> decompressor -> packet-decoder.
            backendChannel.pipeline().addAfter(ProtocolConstants.FRAME_DECODER,
                    ProtocolConstants.COMPRESSION_DECODER, new PacketDecompressor());
            // Outbound: packet-encoder -> compressor -> frame-prepender.
            backendChannel.pipeline().addAfter(ProtocolConstants.FRAME_PREPENDER,
                    ProtocolConstants.COMPRESSION_ENCODER, new PacketCompressor(threshold));
            log.debug("Backend {}:{} enabled compression with threshold {}", server.getHost(), server.getPort(), threshold);
        }

        private void onLoginSuccess() {
            int version = user.getProtocolVersion();

            // 1.20.2+: the backend stays in LOGIN until the client acknowledges Login Success and
            // the proxy drives it into the configuration phase (BungeeCord cutThrough). The
            // configuration handshake (NeoForge/Fabric) then flows through the bridges.
            if (version >= ProtocolConstants.MINECRAFT_1_20_2) {
                // The backend decode switches to CONFIGURATION right away: conforming backends
                // wait for LoginAcknowledged before sending anything, but if one starts its
                // configuration phase immediately (or the client's ack is delayed), the packets
                // must already decode as configuration packets — never as LOGIN.
                backendChannel.pipeline().get(PacketDecoder.class).setProtocol(Protocol.CONFIGURATION);
                log.debug("{}: backend {}:{} accepted login; backend decode -> CONFIGURATION",
                        user.getUsername(), server.getHost(), server.getPort());
                if (serverSwitch) {
                    // Move the client back from GAME into the configuration phase. Encoding the
                    // StartConfiguration packet switches the front-end outbound codec to
                    // CONFIGURATION (nextProtocol), and the client's own StartConfiguration ack
                    // will advance the inbound codec and drive the backend into configuration too.
                    user.sendPacket(new StartConfiguration());
                    installDownstreamBridge(null);
                    // The switch re-runs the 1.20.2+ configuration handshake, so the client's
                    // StartConfiguration ack and config frames must be read again (transfer()
                    // paused reads). UpstreamBridge discards the stale GAME frames queued before
                    // the switch; everything from the ack onward is configuration traffic.
                    user.getChannel().eventLoop().execute(() -> user.getChannel().config().setAutoRead(true));
                } else {
                    // First connection: send Login Success (still LOGIN state), then switch the
                    // front-end outbound codec to CONFIGURATION and install both bridges. Reading
                    // is resumed so the client's LoginAcknowledged can arrive.
                    LoginSuccess sentLogin = loginSuccess();
                    dumpLoginSuccess(sentLogin);
                    user.sendPacket(sentLogin);
                    installDownstreamBridge(() -> initialHandler.onConfigurationDone(server));
                    user.getChannel().eventLoop().execute(() -> initialHandler.enterConfiguration(server));
                }
                return;
            }

            // Pre-1.20.2: switch the backend to the play-phase protocol. The bridge is deliberately
            // NOT installed yet: the JoinGame packet is intercepted below and relayed to the client
            // first (BungeeCord cut-through), so no backend traffic can precede the world reset.
            backendChannel.pipeline().get(PacketDecoder.class).setProtocol(Protocol.GAME);
            backendChannel.pipeline().get(PacketEncoder.class).setProtocol(Protocol.GAME);

            if (serverSwitch) {
                return;
            }

            // First connection: send Login Success to the client. Pre-1.19.3 backends emit PLAY
            // packets immediately after Login Success, so it must be written before any backend
            // traffic is relayed.
            LoginSuccess sentLogin = loginSuccess();
            dumpLoginSuccess(sentLogin);
            user.sendPacket(sentLogin);
            log.debug("LoginSuccess sent to {} ({}) for protocol {}", user.getUsername(), user.getUuid(),
                    user.getProtocolVersion());

            // The client is in the play state from its own perspective once Login Success is
            // sent; Forge backends start the FML handshake right away (before the JoinGame), so
            // the front-end must switch to GAME and install the relay bridge now (BungeeCord
            // InitialHandler.handle(LoginSuccess)).
            user.getChannel().eventLoop().execute(() -> initialHandler.onLoginSuccess(server));
        }

        private LoginSuccess loginSuccess() {
            return new LoginSuccess(user.getUuid(), user.getUsername(), user.getProperties());
        }

        private void dumpLoginSuccess(LoginSuccess sentLogin) {
            if (!log.isDebugEnabled()) {
                return;
            }
            io.netty.buffer.ByteBuf probe = io.netty.buffer.Unpooled.buffer();
            try {
                sentLogin.write(probe, user.getProtocolVersion());
                log.debug("{}: LoginSuccess wire ({} bytes, protocol {}): {}", user.getUsername(),
                        probe.readableBytes(), user.getProtocolVersion(),
                        java.util.HexFormat.of().formatHex(
                                moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket.toArray(probe)));
            } finally {
                probe.release();
            }
        }

        /**
         * Installs the play-phase {@link DownstreamBridge} on the backend channel and marks it as
         * the user's current server (BungeeCord {@code cutThrough}). The {@code onWorldEnter}
         * callback runs once the configuration phase completes (first join only).
         */
        private void installDownstreamBridge(Runnable onWorldEnter) {
            user.setServer(server);
            HandlerBoss boss = backendChannel.pipeline().get(HandlerBoss.class);
            boss.setHandler(new DownstreamBridge(proxy, user, server, onWorldEnter));
        }

        /**
         * BungeeCord cut-through: mirrors {@code ServerConnector.handleLogin} + {@code cutThrough}.
         * First connections (any version) and 1.16+ switches get the JoinGame written directly to
         * the client, followed (on a switch) by a Respawn with the new server's world data and the
         * removal of everything the old server sent. Pre-1.16 switches perform the legacy respawn
         * dance instead of forwarding the JoinGame. The play-phase bridge is only installed after
         * the world reset, so no stale packet can precede or corrupt it.
         */
        private void onJoinGame(JoinGame login) {
            int version = user.getProtocolVersion();

            // First connection (any version) or 1.16+ switch: the JoinGame is sent directly, so the
            // client re-learns its own entity id from it — both ids become the backend's id
            // (BungeeCord handleLogin first-connection/1.16+ branch).
            if (!serverSwitch || version >= 735) {
                playerState.setClientEntityId(user, login.getEntityId());
                playerState.setServerEntityId(user, login.getEntityId());
                if (!serverSwitch) {
                    // The front-end already switched to GAME and installed the bridge when Login
                    // Success was sent (see onLoginSuccess); resume reading and register the
                    // player now. The JoinGame write below is submitted afterwards, so it is
                    // encoded in the GAME state.
                    user.getChannel().eventLoop().execute(() -> initialHandler.onServerConnected(server));
                }

                user.sendPacket(login);
                if (!serverSwitch) {
                    // First join: register the proxy's own channels with the client and announce
                    // its brand (BungeeCord sends both together with the first JoinGame).
                    user.sendPacket(proxy.getPluginMessageService().registerChannels(version));
                    user.sendPacket(proxy.getPluginMessageService().buildProxyBrand(version));
                }
                if (serverSwitch) {
                    // BungeeCord handleLogin: forget the old server's scoreboard/teams/bossbars,
                    // reset the tab list names, then rebuild the world state with a Respawn.
                    playerState.clearServerState(user, user::sendPacket);
                    proxy.getTabListService().resetTabList(user);
                    user.sendPacket(login.toRespawn());
                }
                playerState.setDimension(user, login.getDimension());
            } else {
                // Pre-1.16 switch: the legacy respawn dance (no JoinGame forwarded). The client
                // never learns a new entity id, so clientEntityId stays stable while only the
                // backend-side id is updated below (BungeeCord handleLogin pre-1.16 branch).
                playerState.clearServerState(user, user::sendPacket);
                proxy.getTabListService().resetTabList(user);
                user.sendPacket(new EntityStatus(playerState.getClientEntityId(user),
                        login.isReducedDebugInfo() ? EntityStatus.DEBUG_INFO_REDUCED : EntityStatus.DEBUG_INFO_NORMAL));
                if (version >= 573) {
                    user.sendPacket(new GameState(GameState.IMMEDIATE_RESPAWN, login.isNormalRespawn() ? 0 : 1));
                }
                playerState.setDimensionChange(user, true);
                Object currentDimension = playerState.getDimension(user);
                if (currentDimension != null && login.getDimension() != null
                        && login.getDimension().equals(currentDimension)) {
                    user.sendPacket(login.toRespawn(((Integer) login.getDimension()) >= 0 ? -1 : 0));
                }
                playerState.setServerEntityId(user, login.getEntityId());
                user.sendPacket(login.toRespawn());
                if (version >= 477) {
                    user.sendPacket(new ViewDistance(login.getViewDistance()));
                }
                playerState.setDimension(user, login.getDimension());
            }

            // BungeeCord handleLogin: register the proxy's own channels with the backend, replay
            // the client's brand, and re-register the channels the client declared (so Bukkit
            // plugin messaging keeps working after a switch). The 1.20.2+ configuration phase is
            // relayed verbatim (no CONFIGURATION protocol state yet), so like BungeeCord the proxy
            // channels are only announced to pre-1.20.2 backends here.
            if (version < ProtocolConstants.MINECRAFT_1_20_2) {
                server.sendPacket(proxy.getPluginMessageService().registerChannels(version));
                PluginMessage brandMessage = user.getBrandMessage();
                if (brandMessage != null) {
                    server.sendPacket(brandMessage);
                }
                // Replay the client's settings (skinParts → cape + second skin layer) so a switched
                // backend broadcasts the correct skin layers instead of all-off (BungeeCord
                // ServerConnector parity: ch.write(con.getSettings()) for pre-1.20.2).
                ClientSettings clientSettings =
                        user.getClientSettings();
                if (clientSettings != null) {
                    server.sendPacket(clientSettings);
                }
            }
            Set<String> registeredChannels = user.getRegisteredChannels();
            if (!registeredChannels.isEmpty()) {
                server.sendPacket(new PluginMessage(
                        version >= ProtocolConstants.MINECRAFT_1_13 ? "minecraft:register" : "REGISTER",
                        String.join("\0", registeredChannels).getBytes(StandardCharsets.UTF_8)));
            }
            // Deliver any plugin messages queued for this backend while it had no players
            // (BungeeCord's per-server packet queue, used by the Forward sub-command).
            proxy.getPluginMessageService().drainQueue(backend, server);

            // A vanilla client completes the Forge handshake immediately (BungeeCord parity).
            if (user.getForgeClientHandler().getClientModList() == null
                    && !user.getForgeClientHandler().isHandshakeComplete()) {
                user.getForgeClientHandler().setHandshakeComplete();
            }

            // cutThrough: swap the current server, then install the play-phase bridge.
            user.setServer(server);
            HandlerBoss boss = backendChannel.pipeline().get(HandlerBoss.class);
            boss.setHandler(new DownstreamBridge(proxy, user, server));

            if (serverSwitch) {
                // The switch finished: the client received the new JoinGame, so its GAME frames
                // are legitimate again (pre-1.20.2 switches resume reads here).
                user.setSwitchingServer(false);
                user.getChannel().eventLoop().execute(() -> {
                    user.getChannel().config().setAutoRead(true);
                    sendTabHeaderFooter();
                });
                log.debug("{} ({}) switched to backend {}:{}", user.getUsername(), user.getUuid(),
                        server.getHost(), server.getPort());
            }
        }

        /**
         * Sends the configured TabList header/footer to the client when enabled. Used on server
         * switches, where the front-end codecs are already in the GAME state. Backend-sent
         * header/footer packets are normalised by the TabList interception in DownstreamBridge.
         */
        private void sendTabHeaderFooter() {
            TabListHeaderFooter tabHeader = proxy.getTabListService()
                    .buildHeaderFooter(user, proxy.getOnlineCount());
            if (tabHeader != null) {
                user.sendPacket(tabHeader);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // 限流：后端登录阶段异常同样是「每个玩家一条」，集中重连时会刷屏。
            String gate = LogThrottle.acquire("backend-login-error", 10_000L);
            if (gate != null) {
                log.warn("Backend login error for {},[{}]{}", user.getUsername(), cause.getMessage(), gate);
            }
            user.close();
        }
    }
}
