package moe.koseirin.nyanruaineo.Minecraft.handler;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.command.CommandSender;
import moe.koseirin.nyanruaineo.Minecraft.command.PlayerCommandSender;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerDisconnectEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.PluginMessageEvent;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeConstants;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketEncoder;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.EntityRewrite;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolConstants;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Chat;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientChat;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientCommand;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientSettings;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginAcknowledged;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PluginMessage;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StartConfiguration;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabCompleteRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.UnsignedClientCommand;

/**
 * Relays client-to-server traffic to the backend, mirroring BungeeCord's {@code UpstreamBridge}.
 * Installed on the front-end channel once the play phase begins. Decodes chat/command and
 * tab-completion packets, dispatches player commands to the registered {@code ProxyCommand}s, and
 * forwards everything that was not consumed.
 */
@Slf4j
public class UpstreamBridge extends ChannelInboundHandlerAdapter {

    private final MinecraftProxy proxy;
    private final UserConnection user;

    public UpstreamBridge(MinecraftProxy proxy, ServerConnection server) {
        this.proxy = proxy;
        this.user = server.getUser();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ServerConnection server = user.getServer();
        if (server == null || !server.getChannel().isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }

        // Discard raw frames the client sent in the play phase while a server switch is still
        // running (BungeeCord UpstreamBridge.shouldHandle). transfer() pauses client reads, so the
        // first frames read after onLoginSuccess resumes them are stale GAME packets (movement,
        // keep-alive, ...) queued before the switch; they must not reach a backend that is still in
        // LOGIN/CONFIGURATION. The re-configuration handshake keeps flowing because the client's
        // configuration-phase frames decode as CONFIGURATION and the phase-advancing packets
        // (StartConfiguration/FinishConfiguration) are registered DefinedPackets handled above.
        if (msg instanceof ByteBuf && user.isSwitchingServer()) {
            Protocol frontDecode = user.getChannel().pipeline().get(PacketDecoder.class).getProtocol();
            if (frontDecode == Protocol.GAME) {
                ReferenceCountUtil.release(msg);
                return;
            }
        }

        // BungeeCord-style command interception: the chat/command packets are decoded by the
        // pipeline; the proxy dispatches to its registered commands first, and only unhandled ones
        // are forwarded to the backend.
        if (msg instanceof Chat chat) {
            String message = chat.getMessage().trim();
            if (message.startsWith("/") && dispatchCommand(message.substring(1))) {
                log.debug("Command handled by proxy: {} issued '{}'", user.getUsername(), message);
                return;
            }
            server.getChannel().writeAndFlush(chat, server.getChannel().voidPromise());
            return;
        }

        if (msg instanceof ClientCommand command) {
            // The 1.19+ command field carries no leading slash.
            if (dispatchCommand(command.getCommand().trim())) {
                log.debug("Command handled by proxy: {} issued '/{}'", user.getUsername(), command.getCommand());
                return;
            }
            server.getChannel().writeAndFlush(command, server.getChannel().voidPromise());
            return;
        }

        // 1.20.5+ unsigned command: the client cannot sign a command that is not in its tree, so
        // it sends this variant instead — intercept it exactly like the signed one.
        if (msg instanceof UnsignedClientCommand command) {
            if (dispatchCommand(command.getCommand().trim())) {
                log.debug("Command handled by proxy: {} issued '/{}'", user.getUsername(), command.getCommand());
                return;
            }
            server.getChannel().writeAndFlush(command, server.getChannel().voidPromise());
            return;
        }

        // Tab completion for the proxy's own commands (the ask-server suggestion in the injected
        // tree). Non-proxy commands are forwarded to the backend.
        if (msg instanceof TabCompleteRequest request) {
            if (handleTabComplete(request)) {
                return;
            }
            server.getChannel().writeAndFlush(request, server.getChannel().voidPromise());
            return;
        }

        if (msg instanceof ClientChat chat) {
            String message = chat.getMessage().trim();
            if (message.startsWith("/") && dispatchCommand(message.substring(1))) {
                log.debug("Command handled by proxy: {} issued '{}'", user.getUsername(), message);
                return;
            }
            server.getChannel().writeAndFlush(chat, server.getChannel().voidPromise());
            return;
        }

        // Plugin messages: the client may never talk on the proxy's own BungeeCord channel; the
        // Forge handshake packets are driven by the client handler; REGISTER/UNREGISTER/brand are
        // tracked so a server switch can replay them; everything else is forwarded.
        if (msg instanceof PluginMessage pluginMessage) {
            if (handlePluginMessage(pluginMessage, server)) {
                return;
            }
            server.getChannel().writeAndFlush(pluginMessage, server.getChannel().voidPromise());
            return;
        }

        // 1.20.2+ client information (ClientSettings): remember it and forward it. The backend
        // uses skinParts to broadcast the player's cape/skin layers; configureServer replays it to
        // a new backend on a switch (BungeeCord UpstreamBridge.handle(ClientSettings) parity).
        if (msg instanceof ClientSettings clientSettings) {
            user.setClientSettings(clientSettings);
            server.getChannel().writeAndFlush(clientSettings, server.getChannel().voidPromise());
            return;
        }

        // 1.20.2+ configuration phase: the client's LoginAcknowledged (initial join) or
        // StartConfiguration ack (server switch) drives the backend into the configuration phase.
        // The front-end inbound codec was already advanced to CONFIGURATION by nextProtocol().
        if (msg instanceof LoginAcknowledged || msg instanceof StartConfiguration) {
            log.debug("{}: client sent {} -> configureServer (frontend decode now {})",
                    user.getUsername(), msg.getClass().getSimpleName(),
                    user.getChannel().pipeline().get(PacketDecoder.class).getProtocol());
            configureServer(server);
            return;
        }

        if (log.isDebugEnabled() && msg instanceof ByteBuf buf && buf.isReadable()) {
            try {
                int packetId = DefinedPacket.readVarInt(buf.duplicate());
                log.debug("Upstream client -> {}: packetId=0x{}, bytes={}", server.getHost(),
                        Integer.toHexString(packetId), buf.readableBytes());
            } catch (Exception ignored) {
                // Never fail forwarding because of a diagnostic read.
            }
        }

        // Entity id rewrite (BungeeCord UpstreamBridge parity): translate the client's stable
        // entity id back to the backend's fresh id in serverbound entity-addressed frames
        // (Use Entity / Entity Action) after a pre-1.16 switch.
        EntityRewrite entityRewrite = EntityRewrite.forVersion(user.getProtocolVersion());
        if (entityRewrite != null && msg instanceof ByteBuf raw && raw.isReadable()) {
            entityRewrite.rewriteServerbound(raw,
                    proxy.getPlayerStateService().getClientEntityId(user),
                    proxy.getPlayerStateService().getServerEntityId(user));
        }

        server.getChannel().writeAndFlush(msg, server.getChannel().voidPromise());
    }

    /**
     * Dispatches one player command line (without the leading slash) to the registered commands.
     *
     * @return true when the proxy handled it (do not forward); false when the command is unknown.
     */
    private boolean dispatchCommand(String commandLine) {
        CommandSender sender = new PlayerCommandSender(user, proxy);
        return proxy.getCommandManager().dispatchCommand(sender, commandLine);
    }

    /**
     * Answers a tab-completion request when the cursor targets a proxy command. Returns true when
     * the request was answered (do not forward); false when it should reach the backend.
     */
    private boolean handleTabComplete(TabCompleteRequest request) {
        CommandSender sender = new PlayerCommandSender(user, proxy);
        java.util.List<String> suggestions = proxy.getCommandManager().tabComplete(sender, request.getCursor());
        if (suggestions == null) {
            return false;
        }
        String cursor = request.getCursor();
        int start = Math.max(cursor.lastIndexOf(' ') + 1, 0);
        int length = cursor.length() - start;
        user.getChannel().writeAndFlush(
                buildTabCompleteResponse(request.getTransactionId(), start, length, suggestions),
                user.getChannel().voidPromise());
        return true;
    }

    /**
     * Builds a raw clientbound command-suggestion response for the front-end GAME codec, which
     * relays it verbatim to the client. The packet id mirrors BungeeCord's {@code TabCompleteResponse}
     * mapping ({@code command_suggestions}): 0x0D (1.19.3), 0x0F (1.19.4-1.20.1 / 1.21.5+),
     * 0x10 (1.20.2-1.21.4). It must NOT be confused with the play-phase {@code cookie_request}
     * packet (0x16/0x15), which previously occupied this slot and made 1.21+ clients fail decoding.
     */
    private io.netty.buffer.ByteBuf buildTabCompleteResponse(int transactionId, int start, int length,
                                                             java.util.List<String> suggestions) {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        int version = user.getProtocolVersion();
        int responseId;
        if (version >= ProtocolConstants.MINECRAFT_1_21_5) {
            responseId = 0x0F; // 1.21.5+
        } else if (version >= ProtocolConstants.MINECRAFT_1_20_2) {
            responseId = 0x10; // 1.20.2-1.21.4
        } else if (version >= ProtocolConstants.MINECRAFT_1_19_4) {
            responseId = 0x0F; // 1.19.4-1.20.1
        } else {
            responseId = 0x0D; // 1.19.3
        }
        DefinedPacket.writeVarInt(responseId, buf); // ClientboundCommandSuggestionsPacket
        DefinedPacket.writeVarInt(transactionId, buf);
        DefinedPacket.writeVarInt(start, buf);
        DefinedPacket.writeVarInt(length, buf);
        DefinedPacket.writeVarInt(suggestions.size(), buf);
        for (String suggestion : suggestions) {
            DefinedPacket.writeString(suggestion, buf);
            buf.writeBoolean(false); // no tooltip
        }
        return buf;
    }

    /**
     * Intercepts one client→server plugin message, mirroring BungeeCord's
     * {@code UpstreamBridge.handle(PluginMessage)}. Returns {@code true} when the message was
     * consumed (it must NOT be forwarded to the backend).
     */
    private boolean handlePluginMessage(PluginMessage pluginMessage, ServerConnection server) {
        String tag = pluginMessage.getTag();
        // The client may not use the proxy's own channel (it is reserved for backend plugins).
        if (PluginMessage.BUNGEE_CHANNEL_LEGACY.equals(tag) || PluginMessage.BUNGEE_CHANNEL_MODERN.equals(tag)) {
            return true;
        }

        if (proxy.getProperties().isForgeSupport()) {
            // Hack around Forge race conditions (BungeeCord).
            if (ForgeConstants.FML_TAG.equals(tag) && pluginMessage.getData().length > 0
                    && pluginMessage.getData()[0] == 1) {
                return true;
            }
            // The proxy drives the client-side Forge handshake.
            if (ForgeConstants.FML_HANDSHAKE_TAG.equals(tag)) {
                user.getForgeClientHandler().handle(pluginMessage);
                return true;
            }
            // Drop oversize packets when the backend is not a Forge server (as suggested by
            // BungeeCord, the FML mod list is the only thing allowed to exceed 32 kiB).
            if (server != null && !server.isForgeServer() && pluginMessage.getData().length > Short.MAX_VALUE) {
                return true;
            }
        }

        PluginMessageEvent event = new PluginMessageEvent(user, server, tag, pluginMessage.getData(), false);
        proxy.getEventBus().post(event);
        if (event.isCancelled()) {
            return true;
        }

        // Track channel registrations and the brand; the packet itself is still forwarded below.
        user.trackPluginMessage(pluginMessage);
        return false;
    }

    /**
     * Drives the backend channel into the configuration phase once the client acknowledged Login
     * Success (initial join) or re-entered configuration (server switch), mirroring BungeeCord's
     * {@code UpstreamBridge.configureServer}: the backend decode switches to CONFIGURATION, a fresh
     * {@code LoginAcknowledged} is sent to it (still in LOGIN state), then the proxy registers its
     * own plugin channels and replays the client's brand in the configuration state.
     */
    private void configureServer(ServerConnection server) {
        int version = user.getProtocolVersion();
        // Run on the backend event loop so the decoder/encoder transitions are visible to its
        // decode/encode threads before any backend traffic follows.
        server.getChannel().eventLoop().execute(() -> {
            PacketDecoder decoder = server.getChannel().pipeline().get(PacketDecoder.class);
            PacketEncoder encoder = server.getChannel().pipeline().get(PacketEncoder.class);
            // One-shot guard: the encoder stays LOGIN until this runs (the decoder was already
            // advanced to CONFIGURATION at LoginSuccess).
            if (encoder.getProtocol() == Protocol.LOGIN) {
                decoder.setProtocol(Protocol.CONFIGURATION);
                // Encoded while the encoder is still in LOGIN (LoginAcknowledged is a login packet).
                server.sendPacket(new LoginAcknowledged());
                encoder.setProtocol(Protocol.CONFIGURATION);
                server.sendPacket(proxy.getPluginMessageService().registerChannels(version));
                // Replay the client information (skinParts → cape/skin layers) so a switched
                // backend knows the player's skin layers (BungeeCord configureServer parity).
                if (user.getClientSettings() != null) {
                    server.sendPacket(user.getClientSettings());
                }
                if (user.getBrandMessage() != null) {
                    server.sendPacket(user.getBrandMessage());
                }
                log.debug("{}: drove backend {}:{} into configuration (LoginAcknowledged + registerChannels)",
                        user.getUsername(), server.getHost(), server.getPort());
            }
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        ServerConnection server = user.getServer();
        if (server != null && !server.isClosed()) {
            server.close();
        }
        proxy.playerLeft(user);
        proxy.getPlayerStateService().remove(user);
        proxy.getEventBus().postAsync(new PlayerDisconnectEvent(user.getUsername(), user.getUuid()));
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        ServerConnection server = user.getServer();
        Channel serverChannel = server == null ? null : server.getChannel();
        if (serverChannel != null) {
            // Stop reading from the backend when the client cannot keep up.
            serverChannel.config().setAutoRead(ctx.channel().isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("UpstreamBridge error for {}: {}", user.getUsername(), cause.getMessage());
        ctx.close();
    }
}
