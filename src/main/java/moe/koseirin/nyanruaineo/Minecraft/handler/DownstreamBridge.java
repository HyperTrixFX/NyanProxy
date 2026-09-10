package moe.koseirin.nyanruaineo.Minecraft.handler;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.event.PluginMessageEvent;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecoder;
import moe.koseirin.nyanruaineo.Minecraft.netty.PacketEncoder;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;
import moe.koseirin.nyanruaineo.Minecraft.protocol.EntityRewrite;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolConstants;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.BossBar;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EntityStatus;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.FinishConfiguration;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.JoinGame;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoRemove;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoUpdate;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerListItem;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PluginMessage;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ScoreboardObjective;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ScoreboardScore;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabListHeaderFooter;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Team;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerStateService;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;

/**
 * 负责把后端服务器发来的数据包转发给客户端。
 * 这个处理器在进入游戏阶段后会被挂载到后端连接上。对于 TabList 相关的包，会先交给
 * TabListService 处理再转发，其他包就直接原样传过去。如果玩家切换到了别的服务器，
 * 旧服务器后面再发来的包就会被丢掉，免得混进新世界（类似 BungeeCord 里通过判断
 * con.getServer() == this.server 来防止泄漏一样）。
 */
@Slf4j
public class DownstreamBridge extends ChannelInboundHandlerAdapter {

    private final MinecraftProxy proxy;
    private final UserConnection user;
    private final ServerConnection server;
    private final int generation;
    /**
     * 这个标记在 1.20.2 及以上版本的玩家第一次连上后端时是 true（具体值在配置阶段结束才定下来）。
     * 如果玩家切换了服务器，会在收到 JoinGame 包时走一遍“重置世界”的流程。
     */
    private final boolean firstJoin;
    /**
     * 仅当玩家第一次加入时，在 1.20.2 及以上版本的配置阶段结束后执行一次。
     */
    private Runnable onWorldEnter;
    /**
     * 在配置阶段观察到的服务端 {@code minecraft:command_argument_type} 注册表顺序（解析器 ID → 解析器 ResourceLocation）。
     * 客户端会依据此快照重建其 BuiltInRegistries.COMMAND_ARGUMENT_TYPE，因此游戏阶段的命令树中的解析器 ID
     * 必须与此顺序保持一致。该顺序会与命令树一同记录，便于诊断不匹配问题。
     * tips:这是 Minecraft 1.20.2+ 引入的“配置阶段（Configuration Phase）”机制的一部分。
     * 在配置阶段，服务端会向客户端同步各种注册表（如命令参数类型、粒子效果等）的顺序，确保客户端和服务端对注册表 ID 的映射一致。
     * 命令树中的每个节点会引用一个解析器 ID（整数），这个 ID 实际上是注册表顺序中的索引。如果客户端和服务端的顺序不一致，命令解析就会出错。
     * 代码中会记录这个顺序，以便在出现问题时进行调试。
     */
    private final java.util.Map<Integer, String> serverArgumentTypes = new java.util.TreeMap<>();
    /**
     * 这里存的是服务端完整的命令参数类型注册表顺序（来自原版数据）。我们通过改掉客户端的
     * select_known_packs 包，让它不选任何数据包，这样客户端就会用服务端发来的完整注册表。
     */
    private final java.util.Map<Integer, String> fullServerArgumentTypes = new java.util.TreeMap<>();
    /** 若原版注册表数据已包含完整的命令参数类型顺序，则为 true。 */
    private boolean fullArgumentTypesCaptured;

    public DownstreamBridge(MinecraftProxy proxy, UserConnection user, ServerConnection server) {
        this(proxy, user, server, null);
    }

    public DownstreamBridge(MinecraftProxy proxy, UserConnection user, ServerConnection server,
                            Runnable onWorldEnter) {
        this.proxy = proxy;
        this.user = user;
        this.server = server;
        this.generation = user.getServerGeneration();
        this.onWorldEnter = onWorldEnter;
        this.firstJoin = onWorldEnter != null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        Channel userChannel = user.getChannel();
        if (!userChannel.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        // 陈旧后端防护：一旦玩家切换到其他后端，任何来自此（现已过时的）后端、仍在传输中的数据包都将被丢弃，
        // 以免污染新世界。
        if (user.getServer() != server) {
            ReferenceCountUtil.release(msg);
            return;
        }

        // NeoForge 冻结注册表同步问题：服务端每个连接中 `minecraft:command_argument_type` 的快照可能已过期 ——
        // 该快照取自实时注册表，而世界存档可能在游戏阶段的命令树构建之后对注册表进行了缩减，
        // 导致命令树引用的解析器 ID 在快照中不存在。客户端在解析命令树时，遇到未知的解析器 ID
        // 会跳过该节点而不消耗其属性（导致流漂移，最终报错 "Failed to decode 'clientbound/minecraft:commands'"）。
        // 客户端本地的 command_argument_type 注册表由相同的模组按相同的注册顺序构建，
        // 因此其解析器 ID 与命令树完全一致。跳过冻结同步中的注册表数据，可以让客户端保持
        // 其本地注册表不变（否则冻结同步会将其 ID 数组清空并用过期快照覆盖）。
        if (msg instanceof PluginMessage syncPluginMessage) {
            String syncTag = syncPluginMessage.getTag();
            if ("neoforge:frozen_registry_sync_start".equals(syncTag)) {
                byte[] stripped = stripCommandArgumentTypeFromSyncStart(syncPluginMessage.getData());
                if (stripped != null) {
                    log.debug("{}: removing minecraft:command_argument_type from frozen registry sync", user.getUsername());
                    syncPluginMessage.setData(stripped);
                }
            } else if ("neoforge:frozen_registry".equals(syncTag) && isCommandArgumentTypePayload(syncPluginMessage.getData())) {
                log.debug("{}: dropping stale command_argument_type frozen snapshot (client keeps its local registry)", user.getUsername());
                return; // consumed: must NOT reach the client
            }
        }

        // NeoForge 的冻结注册表同步必须正常进行（客户端需要回复 sync_completed，
        // 才能解除后端 known-packs 任务的阻塞）。此处仅为诊断目的：如果原始注册表数据帧中
        // 包含 command_argument_type 的顺序，则将其捕获（用于调试）。
        if (msg instanceof ByteBuf raw && raw.isReadable()) {
            try {
                if (user.getProtocolVersion() >= ProtocolConstants.MINECRAFT_1_20_2
                        && user.getChannel().pipeline().get(PacketDecoder.class) != null
                        && user.getChannel().pipeline().get(PacketDecoder.class).getProtocol() == Protocol.CONFIGURATION) {
                    int rawId = DefinedPacket.readVarInt(raw.duplicate());
                    if (rawId == 0x07) {
                        captureRegistryData(raw.duplicate());
                    }
                }
            } catch (Exception ignored) {
                // Diagnostics must never break forwarding.
            }
        }

        if (log.isDebugEnabled() && user.getProtocolVersion() >= 764) {
            dumpClientbound(msg);
        }

        // TabList interception: the header/footer is replaced with the configured text and player
        // entry display names are wrapped in the configured prefix/suffix (when enabled).
        if (msg instanceof TabListHeaderFooter headerFooter) {
            proxy.getTabListService().applyHeaderFooter(headerFooter, user, proxy.getOnlineCount());
        } else if (msg instanceof PlayerListItem playerListItem) {
            proxy.getTabListService().decorate(playerListItem, user, proxy.getOnlineUsers());
        } else if (msg instanceof PlayerInfoUpdate playerInfoUpdate) {
            proxy.getTabListService().decorateUpdate(playerInfoUpdate, user, proxy.getOnlineUsers());
        } else if (msg instanceof PlayerInfoRemove playerInfoRemove) {
            proxy.getTabListService().removeEntries(playerInfoRemove, user);
        }

        // Server-side state tracking (BungeeCord DownstreamBridge parity): the switch cleanup
        // needs to know which scoreboard objectives/scores/teams/bossbars this server sent.
        if (msg instanceof ScoreboardObjective objective) {
            proxy.getPlayerStateService().trackObjective(user, objective.getName(), objective.getAction() != 1);
        } else if (msg instanceof ScoreboardScore score) {
            proxy.getPlayerStateService().trackScore(user, score.getItemName(), score.getScoreName(), score.getAction() != 1);
        } else if (msg instanceof Team team) {
            proxy.getPlayerStateService().trackTeam(user, team.getName(), team.getMode() != 1);
        } else if (msg instanceof BossBar bossBar) {
            proxy.getPlayerStateService().trackBossBar(user, bossBar.getUuid(), bossBar.getAction() != 1);
        }

        // Plugin messages: the backend brand is rewritten to the proxy's own, and the BungeeCord
        // channel carries the proxy-side built-in sub-commands; everything else is forwarded.
        if (msg instanceof PluginMessage pluginMessage) {
            if (handlePluginMessage(pluginMessage)) {
                return;
            }
            if (log.isDebugEnabled()) {
                String tag = pluginMessage.getTag();
                byte[] data = pluginMessage.getData() == null ? new byte[0] : pluginMessage.getData();
                String hex = java.util.HexFormat.of().formatHex(data, 0,
                        Math.min(32, data.length));
                // Full dump of the NeoForge frozen-registry / network-registry payloads: the client
                // builds BuiltInRegistries.COMMAND_ARGUMENT_TYPE from these, so they must be audited
                // byte-for-byte against the command tree parser ids.
                boolean fullDump = tag != null && (tag.equals("neoforge:frozen_registry")
                        || tag.equals("neoforge:network") || tag.equals("neoforge:known_registry_data_maps"));
                if (fullDump) {
                    hex = java.util.HexFormat.of().formatHex(data);
                    if (tag.equals("neoforge:frozen_registry")) {
                        byte[] patched = patchFrozenRegistry(data);
                        if (patched != null) {
                            pluginMessage.setData(patched);
                            data = patched;
                            hex = java.util.HexFormat.of().formatHex(data);
                            log.debug("{}: patched command_argument_type frozen snapshot: {} -> {} entries",
                                    user.getUsername(), serverArgumentTypes.size(), data.length);
                        }
                        captureArgumentTypeSnapshot(data);
                    }
                }
                log.debug("{}: relaying play PluginMessage [{}] ({} bytes) data[{}..]{}", user.getUsername(),
                        tag, data.length, hex, fullDump ? " FULL" : "");
            }
            userChannel.writeAndFlush(pluginMessage, userChannel.voidPromise());
            return;
        }

        // 1.20.2+ configuration phase: the backend ends configuration with FinishConfiguration.
        // Forwarding it advances the front-end outbound codec to GAME (nextProtocol); the backend
        // inbound codec was already advanced to GAME when this packet was decoded.
        if (msg instanceof FinishConfiguration finishConfiguration) {
            log.debug("{}: backend {}:{} finished configuration", user.getUsername(),
                    server.getHost(), server.getPort());
            user.sendPacket(finishConfiguration);
            if (onWorldEnter != null) {
                Runnable callback = onWorldEnter;
                onWorldEnter = null;
                callback.run();
            }
            return;
        }

        // 1.20.2+ JoinGame: the bridge is installed before it arrives. First connections were
        // finalized at FinishConfiguration, so this only forwards; switches run the BungeeCord
        // world-reset dance (scoreboard/bossbar cleanup, tab list reset, Respawn). For older
        // versions the bridge is installed after the JoinGame, so it never reaches this branch.
        if (msg instanceof JoinGame joinGame && user.getProtocolVersion() >= 764) {
            handleJoinGame(joinGame);
            return;
        }

        if (log.isDebugEnabled() && msg instanceof ByteBuf buf && buf.isReadable()) {
            try {
                int packetId = DefinedPacket.readVarInt(buf.duplicate());
                log.debug("Downstream {} -> client: packetId=0x{}, bytes={}", user.getUsername(),
                        Integer.toHexString(packetId), buf.readableBytes());
            } catch (Exception ignored) {
                // Never fail forwarding because of a diagnostic read.
            }
        }

        // 1.20.5+ declare_commands: NeoForge servers write every modded argument node with an
        // unresolvable parser id (getId returns -256 with the real id trailing in the property), so
        // ANY client — a NeoForge client whose registry order differs, or a vanilla client with no
        // modded argument types — fails to decode the tree ("Failed to decode
        // 'clientbound/minecraft:commands'"). The command tree only drives the client's
        // tab-completion and client-side parsing; the backend still executes every command from its
        // own dispatcher. Mirroring BungeeCord's DownstreamBridge#handle(Commands), replace the
        // broken backend tree with a synthetic tree that registers the proxy's own commands
        // (literal + a greedy-string "args" child with the ask-server suggestion), so the client's
        // dispatcher is non-empty, /server & co. are sent to the proxy, and joining works
        // universally for any NeoForge/Forge/vanilla client regardless of its registry state.
        // Packet id: 0x0E (1.19.3), 0x10 (1.19.4-1.20.1), 0x11 (1.20.2-1.21.4), 0x10 (1.21.5+).
        if (msg instanceof ByteBuf raw && raw.isReadable()
                && user.getProtocolVersion() >= ProtocolConstants.MINECRAFT_1_19_3) {
            try {
                int packetId = DefinedPacket.readVarInt(raw.duplicate());
                if (packetId == declareCommandsId()) {
                    // Extract the backend's top-level command names so the client can still
                    // tab-complete them (merged into the synthetic tree as ask-server literals).
                    // Falls back to the proxy-only tree when the backend tree can't be walked
                    // (modded argument parsers).
                    java.util.List<String> backendCommands = extractBackendCommandNames(raw);
                    log.debug("{}: replacing backend command tree with proxy command tree ({} backend commands)",
                            user.getUsername(), backendCommands == null ? "unparseable" : backendCommands.size());
                    raw.release();
                    msg = buildProxyCommandTree(backendCommands);
                }
            } catch (Exception ignored) {
                // Never break forwarding because of a replacement attempt.
            }
        }

        // A graceful backend shutdown (`/stop`) does not just drop the connection: Spigot first
        // sends every player a play-state Disconnect carrying `bukkit.yml`'s
        // `settings.shutdown-message` (default "Server closed"), and the CLIENT then disconnects
        // itself — so the "backend connection closed" fallback would never run. Intercept that one
        // packet and route it through the same lobby fallback. Deliberate kicks (plugins, admins)
        // carry other reasons and are forwarded verbatim, so the player still sees their message.
        if (msg instanceof ByteBuf raw && raw.isReadable()
                && isBackendShutdownDisconnect(raw, user.getProtocolVersion())) {
            if (proxy.getPlayerTransferService().fallbackToLobby(user, server.getChannel())) {
                raw.release();
                return;
            }
            // No fallback was available (no lobby configured, or the lobby is the server that just
            // shut down): fall through and forward the backend's own disconnect screen.
        }

        // Entity id rewrite (BungeeCord DownstreamBridge parity): on a pre-1.16 server switch the
        // client never got a new JoinGame, so it keeps its original entity id while the backend
        // assigned a fresh one. Translate the backend id back to the client's stable id in every
        // entity-addressed frame, otherwise velocity/status/metadata are silently dropped.
        EntityRewrite entityRewrite = EntityRewrite.forVersion(user.getProtocolVersion());
        if (entityRewrite != null) {
            PlayerStateService playerState = proxy.getPlayerStateService();
            int serverEntityId = playerState.getServerEntityId(user);
            int clientEntityId = playerState.getClientEntityId(user);
            if (msg instanceof ByteBuf raw && raw.isReadable()) {
                // Only a switched connection has two different ids, and only entity-addressed frames
                // can change size — everything else (chunk data!) must never be copied.
                if (serverEntityId != clientEntityId && entityRewrite.isRewritableClientbound(raw)) {
                    // The frame is a fixed-capacity slice / array wrapper, but a VarInt entity id
                    // gets longer when the two ids have different VarInt lengths, so the rewrite
                    // needs a buffer it can grow.
                    ByteBuf target = EntityRewrite.growable(raw);
                    if (target != raw) {
                        raw.release();
                        msg = target;
                    }
                    entityRewrite.rewriteClientbound(target, serverEntityId, clientEntityId);
                }
            } else if (msg instanceof EntityStatus status) {
                if (status.getEntityId() == serverEntityId) {
                    status.setEntityId(clientEntityId);
                } else if (status.getEntityId() == clientEntityId) {
                    status.setEntityId(serverEntityId);
                }
            }
        }

        userChannel.writeAndFlush(msg, userChannel.voidPromise());
    }

    /** The clientbound declare_commands packet id for the player's protocol version. */
    private int declareCommandsId() {
        int version = user.getProtocolVersion();
        if (version >= ProtocolConstants.MINECRAFT_1_21_5) {
            return 0x10; // 1.21.5+
        }
        if (version >= ProtocolConstants.MINECRAFT_1_20_2) {
            return 0x11; // 1.20.2-1.21.4
        }
        if (version >= ProtocolConstants.MINECRAFT_1_19_4) {
            return 0x10; // 1.19.4-1.20.1
        }
        return 0x0E;     // 1.19.3
    }

    /**
     * Builds a 1.20.5+ declare_commands payload whose root carries the proxy's own commands plus,
     * when available, the backend's top-level command names (each as a literal with a greedy-string
     * {@code args} child using the {@code minecraft:ask_server} suggestion, mirroring BungeeCord's
     * {@code DownstreamBridge#handle(Commands)}). The backend names keep the client's dispatcher
     * aware of backend commands so they tab-complete and are not shown as "unknown command", while
     * their arguments still round-trip to the backend via ask-server. {@code backendCommands} may be
     * {@code null} (unparseable backend tree) or empty.
     */
    private ByteBuf buildProxyCommandTree(java.util.List<String> backendCommands) {
        // Ordered, de-duplicated command names: proxy commands first (they take precedence), then
        // backend command names the proxy does not already own.
        java.util.LinkedHashSet<String> commands = new java.util.LinkedHashSet<>();
        java.util.List<String> proxyCommands = proxy.getCommandManager().getCommandNames();
        if (proxyCommands != null) {
            commands.addAll(proxyCommands);
        }
        if (backendCommands != null) {
            commands.addAll(backendCommands);
        }
        java.util.List<String> commandList = new java.util.ArrayList<>(commands);
        int commandCount = commandList.size();
        // root + (literal + args) per command
        int nodeCount = 1 + commandCount * 2;

        ByteBuf tree = Unpooled.buffer();
        try {
            DefinedPacket.writeVarInt(declareCommandsId(), tree); // declare_commands
            DefinedPacket.writeVarInt(nodeCount, tree);     // node count

            // node 0: the root node (flags ROOT, one child per command literal).
            DefinedPacket.writeVarInt(0x00, tree);          // flags: ROOT
            DefinedPacket.writeVarInt(commandCount, tree);  // children count
            for (int c = 0; c < commandCount; c++) {
                DefinedPacket.writeVarInt(1 + c * 2, tree); // child = literal node index
            }

            for (int c = 0; c < commandCount; c++) {
                int literalIndex = 1 + c * 2;
                int argsIndex = literalIndex + 1;

                // literal node: flags LITERAL | HAS_COMMAND, one child (args), name.
                DefinedPacket.writeVarInt(0x05, tree);
                DefinedPacket.writeVarInt(1, tree);
                DefinedPacket.writeVarInt(argsIndex, tree);
                DefinedPacket.writeString(commandList.get(c), tree);

                // args node: flags ARGUMENT | HAS_COMMAND | SUGGESTIONS, greedy string, ask-server.
                DefinedPacket.writeVarInt(0x16, tree);
                DefinedPacket.writeVarInt(0, tree);
                DefinedPacket.writeString("args", tree);
                DefinedPacket.writeVarInt(5, tree);          // parser id: brigadier:string
                DefinedPacket.writeVarInt(2, tree);          // StringType: GREEDY_PHRASE
                DefinedPacket.writeString("minecraft:ask_server", tree);
            }

            // root index
            DefinedPacket.writeVarInt(0, tree);
            return tree;
        } catch (Exception e) {
            tree.release();
            throw e;
        }
    }

    /**
     * Best-effort extraction of the backend's top-level command names (literals directly under the
     * root) from a raw declare_commands frame. Returns {@code null} when the tree cannot be walked
     * reliably (modded argument parsers or a malformed frame), so the caller falls back to the
     * proxy-only tree.
     */
    private java.util.List<String> extractBackendCommandNames(ByteBuf raw) {
        ByteBuf buf = raw.duplicate();
        try {
            DefinedPacket.readVarInt(buf); // packet id
            int nodeCount = DefinedPacket.readVarInt(buf);
            if (nodeCount <= 0 || nodeCount > 8192) {
                return null;
            }

            String[] names = new String[nodeCount];
            int[][] children = new int[nodeCount][];
            for (int i = 0; i < nodeCount; i++) {
                if (!buf.isReadable()) {
                    return null;
                }
                int flags = buf.readUnsignedByte();
                int type = flags & 0x03;

                int childCount = DefinedPacket.readVarInt(buf);
                int[] childIds = new int[childCount];
                for (int c = 0; c < childCount; c++) {
                    if (!buf.isReadable()) {
                        return null;
                    }
                    childIds[c] = DefinedPacket.readVarInt(buf);
                }
                children[i] = childIds;

                if ((flags & 0x08) != 0) {
                    if (!buf.isReadable()) {
                        return null;
                    }
                    DefinedPacket.readVarInt(buf); // redirect
                }

                String name = null;
                if (type == 1) {
                    name = DefinedPacket.readString(buf);
                } else if (type == 2) {
                    name = DefinedPacket.readString(buf);
                    int parser = DefinedPacket.readVarInt(buf);
                    if (!skipParserProperties(buf, parser)) {
                        return null; // modded parser → can't walk
                    }
                    if ((flags & 0x10) != 0) {
                        DefinedPacket.readString(buf); // suggestions provider
                    }
                } else if (type != 0) {
                    return null;
                }
                names[i] = name;
            }

            if (!buf.isReadable()) {
                return null;
            }
            int rootIndex = DefinedPacket.readVarInt(buf);
            if (buf.isReadable()) {
                return null; // drift: consumed bytes don't line up
            }
            if (rootIndex < 0 || rootIndex >= nodeCount) {
                return null;
            }

            java.util.List<String> result = new java.util.ArrayList<>();
            for (int child : children[rootIndex]) {
                if (child >= 0 && child < nodeCount && names[child] != null && !names[child].isEmpty()) {
                    result.add(names[child]);
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Intercepts one server→client plugin message, mirroring BungeeCord's
     * {@code DownstreamBridge.handle(PluginMessage)}. Returns {@code true} when the message was
     * consumed (it must NOT be forwarded to the client).
     */
    private boolean handlePluginMessage(PluginMessage pluginMessage) {
        PluginMessageEvent event = new PluginMessageEvent(user, server, pluginMessage.getTag(),
                pluginMessage.getData(), true);
        proxy.getEventBus().post(event);
        if (event.isCancelled()) {
            return true;
        }

        String tag = pluginMessage.getTag();
        // Brand rewrite (BungeeCord): the client should see the proxy's brand, not the backend's.
        String brandChannel = user.getProtocolVersion() >= 393 ? "minecraft:brand" : "MC|Brand";
        if (brandChannel.equals(tag)) {
            ByteBuf brand = Unpooled.wrappedBuffer(pluginMessage.getData());
            String serverBrand = DefinedPacket.readString(brand);
            brand.release();

            if (serverBrand.contains(proxy.getProperties().getProxyName())) {
                throw new IllegalStateException("Cannot connect proxy to itself!");
            }

            // The payload is a VarInt-prefixed string; rewrite it to "proxyName (version) <- backend".
            ByteBuf rewritten = Unpooled.buffer();
            DefinedPacket.writeString(proxy.getProperties().getProxyName() + " (1.0) <- " + serverBrand, rewritten);
            pluginMessage.setData(DefinedPacket.toArray(rewritten));
            rewritten.release();
            // The packet was already decoded, so write the modified copy manually.
            user.sendPacket(pluginMessage);
            return true;
        }

        // The BungeeCord channel is handled by the proxy and never reaches the client.
        if (PluginMessage.BUNGEE_CHANNEL_LEGACY.equals(tag)) {
            proxy.getPluginMessageService().handleBungeeCordChannel(user, server, pluginMessage.getData());
            return true;
        }
        return false;
    }

    /**
     * Handles a 1.20.2+ JoinGame, mirroring BungeeCord's {@code ServerConnector.handleLogin}
     * 1.16+ branch: forwards the JoinGame, then (on a switch) emits the removals for everything
     * the old server sent and a Respawn so the client rebuilds the world state.
     */
    private void handleJoinGame(JoinGame login) {
        PlayerStateService playerState = proxy.getPlayerStateService();
        playerState.setClientEntityId(user, login.getEntityId());
        playerState.setServerEntityId(user, login.getEntityId());

        user.sendPacket(login);
        if (!firstJoin) {
            // 1.20.2+ switch: the new JoinGame arrived, so the client's GAME frames are legitimate
            // again — stop discarding them (the switch flag was set in PlayerTransferService).
            user.setSwitchingServer(false);
            playerState.clearServerState(user, user::sendPacket);
            proxy.getTabListService().resetTabList(user);
            user.sendPacket(login.toRespawn());
            // The client cleared the header/footer on JoinGame; a switch does not go through
            // playerJoined()/refreshTabList(), so re-apply it here (BungeeCord setTabHeader parity).
            proxy.getTabListService().pushHeaderFooter(user, proxy.getOnlineCount());
        }
        playerState.setDimension(user, login.getDimension());
    }

    /**
     * Captures the FULL server {@code minecraft:command_argument_type} order from a vanilla
     * configuration registry-data frame (0x7). Format: [registryId string][count varint]
     * [entries: (key string + optional NBT value)*count]; the entry order is the registry id.
     */
    private void captureRegistryData(ByteBuf buf) {
        try {
            buf.skipBytes(1); // packet id
            String registryId = DefinedPacket.readString(buf);
            if (!"minecraft:command_argument_type".equals(registryId)) {
                return;
            }
            int count = DefinedPacket.readVarInt(buf);
            fullServerArgumentTypes.clear();
            for (int i = 0; i < count && buf.isReadable(); i++) {
                String key = DefinedPacket.readString(buf);
                fullServerArgumentTypes.put(i, key);
                skipOptionalNbt(buf);
            }
            fullArgumentTypesCaptured = true;
            log.debug("{}: captured FULL command_argument_type registry data: {} entries, id101={}",
                    user.getUsername(), fullServerArgumentTypes.size(), fullServerArgumentTypes.get(101));
        } catch (Exception e) {
            log.debug("{}: failed to capture command_argument_type registry data: {}", user.getUsername(), e.toString());
        }
    }

    /**
     * Extends the NeoForge {@code minecraft:command_argument_type} frozen snapshot with every
     * parser id the full registry data knows but the snapshot omits, so the client's post-freeze
     * registry covers every id referenced by the play-phase command tree. Returns null when the
     * payload is not the command_argument_type snapshot or nothing needs patching.
     */
    private byte[] patchFrozenRegistry(byte[] data) {
        if (!fullArgumentTypesCaptured || fullServerArgumentTypes.isEmpty()) {
            return null;
        }
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(data);
        try {
            String registryName = DefinedPacket.readString(buf);
            if (!"minecraft:command_argument_type".equals(registryName)) {
                return null;
            }
            int count = DefinedPacket.readVarInt(buf);
            int maxId = -1;
            var knownIds = new java.util.HashSet<Integer>();
            for (int i = 0; i < count; i++) {
                int id = DefinedPacket.readVarInt(buf);
                DefinedPacket.readString(buf);
                knownIds.add(id);
                if (id > maxId) {
                    maxId = id;
                }
            }
            // Missing ids: entries the full registry knows that the snapshot lacks and that lie
            // beyond the snapshot's range (remapping lower ids would corrupt node properties).
            var missing = new java.util.ArrayList<Integer>();
            for (var e : fullServerArgumentTypes.entrySet()) {
                if (e.getKey() > maxId && !knownIds.contains(e.getKey())) {
                    missing.add(e.getKey());
                }
            }
            if (missing.isEmpty()) {
                return null;
            }
            // Rebuild: [registryName][newCount][existing entries verbatim][missing entries][aliases verbatim].
            io.netty.buffer.ByteBuf src = io.netty.buffer.Unpooled.wrappedBuffer(data);
            io.netty.buffer.ByteBuf out = io.netty.buffer.Unpooled.buffer();
            try {
                DefinedPacket.readString(src); // registry name
                int oldCount = DefinedPacket.readVarInt(src);
                DefinedPacket.writeString(registryName, out);
                DefinedPacket.writeVarInt(oldCount + missing.size(), out);
                for (int i = 0; i < oldCount; i++) {
                    int id = DefinedPacket.readVarInt(src);
                    String name = DefinedPacket.readString(src);
                    DefinedPacket.writeVarInt(id, out);
                    DefinedPacket.writeString(name, out);
                }
                missing.sort(Integer::compareTo);
                for (int id : missing) {
                    DefinedPacket.writeVarInt(id, out);
                    DefinedPacket.writeString(fullServerArgumentTypes.get(id), out);
                }
                int aliasCount = DefinedPacket.readVarInt(src);
                DefinedPacket.writeVarInt(aliasCount, out);
                for (int i = 0; i < aliasCount; i++) {
                    String a = DefinedPacket.readString(src);
                    String b = DefinedPacket.readString(src);
                    DefinedPacket.writeString(a, out);
                    DefinedPacket.writeString(b, out);
                }
            } finally {
                src.release();
            }
            byte[] result = DefinedPacket.toArray(out);
            out.release();
            log.debug("{}: command_argument_type snapshot patched: {} -> {} entries (added ids {})",
                    user.getUsername(), count, count + missing.size(), missing);
            return result;
        } catch (Exception e) {
            log.debug("{}: failed to patch command_argument_type snapshot: {}", user.getUsername(), e.toString());
            return null;
        } finally {
            buf.release();
        }
    }

    /** Skips one anonymous NBT tag (no name prefix). */
    private void skipOptionalNbt(io.netty.buffer.ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }
        skipNbt(buf);
    }

    /** Skips an NBT payload given its type, without a leading type byte or name (list elements). */
    private void skipNbtPayload(io.netty.buffer.ByteBuf buf, int tag) {
        switch (tag) {
            case 1 -> buf.skipBytes(1);
            case 2 -> buf.skipBytes(2);
            case 3, 5 -> buf.skipBytes(4);
            case 4, 6 -> buf.skipBytes(8);
            case 7, 11, 12 -> {
                int n = DefinedPacket.readVarInt(buf);
                int size = switch (tag) {
                    case 7 -> 1;
                    case 11 -> 4;
                    default -> 8;
                };
                buf.skipBytes(n * size);
            }
            case 8 -> DefinedPacket.readString(buf);
            case 9 -> {
                int elemType = buf.readByte() & 0xFF;
                int n = DefinedPacket.readVarInt(buf);
                for (int i = 0; i < n; i++) {
                    if (elemType == 0) {
                        continue;
                    }
                    if (elemType == 10) {
                        skipNbt(buf);
                    } else {
                        skipNbtPayload(buf, elemType);
                    }
                }
            }
            case 10 -> {
                while (true) {
                    int t = buf.getByte(buf.readerIndex()) & 0xFF;
                    if (t == 0) {
                        buf.skipBytes(1);
                        break;
                    }
                    DefinedPacket.readString(buf);
                    skipNbt(buf);
                }
            }
            case 13 -> {
                int n = DefinedPacket.readVarInt(buf);
                buf.skipBytes(n);
            }
            default -> throw new IllegalStateException("unknown nbt tag " + tag);
        }
    }

    private void skipNbt(io.netty.buffer.ByteBuf buf) {
        int tag = buf.readByte() & 0xFF;
        if (tag == 0) {
            return;
        }
        switch (tag) {
            case 1, 2 -> buf.skipBytes(2);
            case 3, 5 -> buf.skipBytes(4);
            case 4, 6 -> buf.skipBytes(8);
            case 7, 11, 12 -> {
                int n = DefinedPacket.readVarInt(buf);
                int size = switch (tag) {
                    case 7 -> 1;
                    case 11 -> 4;
                    default -> 8;
                };
                buf.skipBytes(n * size);
            }
            case 8 -> DefinedPacket.readString(buf);
            case 9 -> {
                int elemType = buf.readByte() & 0xFF;
                int n = DefinedPacket.readVarInt(buf);
                for (int i = 0; i < n; i++) {
                    if (elemType == 0) {
                        continue;
                    }
                    if (elemType == 10) {
                        // A compound list element is a full named tag (type byte + name + payload).
                        skipNbt(buf);
                    } else {
                        // Non-compound list elements carry neither a type byte nor a name.
                        skipNbtPayload(buf, elemType);
                    }
                }
            }
            case 10 -> {
                while (true) {
                    int t = buf.getByte(buf.readerIndex()) & 0xFF;
                    if (t == 0) {
                        buf.skipBytes(1);
                        break;
                    }
                    DefinedPacket.readString(buf);
                    skipNbt(buf);
                }
            }
            case 13 -> {
                int n = DefinedPacket.readVarInt(buf);
                buf.skipBytes(n);
            }
            default -> throw new IllegalStateException("unknown nbt tag " + tag);
        }
    }

    /**
     * Returns true when a {@code neoforge:frozen_registry} payload carries the
     * {@code minecraft:command_argument_type} registry. Payload layout:
     * [registryName string][ids map][aliases map].
     */
    private boolean isCommandArgumentTypePayload(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(data);
        try {
            String registryName = DefinedPacket.readString(buf);
            return "minecraft:command_argument_type".equals(registryName);
        } catch (Exception e) {
            return false;
        } finally {
            buf.release();
        }
    }

    /**
     * Rebuilds a {@code neoforge:frozen_registry_sync_start} payload without the
     * {@code minecraft:command_argument_type} entry, so the client does not expect a snapshot we
     * deliberately drop. Payload layout: [count varint][ResourceLocation strings...]. Returns null
     * when the payload does not contain that registry (nothing to strip).
     */
    private byte[] stripCommandArgumentTypeFromSyncStart(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(data);
        io.netty.buffer.ByteBuf out = io.netty.buffer.Unpooled.buffer();
        try {
            int count = DefinedPacket.readVarInt(buf);
            var names = new java.util.ArrayList<String>(count);
            for (int i = 0; i < count; i++) {
                names.add(DefinedPacket.readString(buf));
            }
            names.remove("minecraft:command_argument_type");
            if (names.size() == count) {
                return null; // nothing stripped
            }
            DefinedPacket.writeVarInt(names.size(), out);
            for (String name : names) {
                DefinedPacket.writeString(name, out);
            }
            return DefinedPacket.toArray(out);
        } catch (Exception e) {
            return null;
        } finally {
            buf.release();
            out.release();
        }
    }

    /**
     * Parses a NeoForge {@code frozen_registry} payload and, when it carries the
     * {@code minecraft:command_argument_type} registry, records the server's parser id → name order.
     * Payload layout: [registryName string][ids map: count + (varint id + ResourceLocation)*n]
     * [aliases map: count + (ResourceLocation + ResourceLocation)*n].
     */
    private void captureArgumentTypeSnapshot(byte[] data) {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(data);
        try {
            String registryName = DefinedPacket.readString(buf);
            if (!"minecraft:command_argument_type".equals(registryName)) {
                return;
            }
            serverArgumentTypes.clear();
            int count = DefinedPacket.readVarInt(buf);
            for (int i = 0; i < count; i++) {
                int id = DefinedPacket.readVarInt(buf);
                String name = DefinedPacket.readString(buf);
                serverArgumentTypes.put(id, name);
            }
            log.debug("{}: captured command_argument_type snapshot: {} entries, e.g. id5={} id6={} id101={}",
                    user.getUsername(), serverArgumentTypes.size(),
                    serverArgumentTypes.get(5), serverArgumentTypes.get(6),
                    serverArgumentTypes.get(101));
        } catch (Exception e) {
            log.debug("{}: failed to parse command_argument_type snapshot: {}", user.getUsername(), e.toString());
        } finally {
            buf.release();
        }
    }

    /**
     * Best-effort audit of the play-phase command tree (declare_commands, 1.20.5+): walks the node
     * list using the vanilla parser property sizes and logs the parser ids the server referenced,
     * so they can be compared against the captured command_argument_type snapshot. Stops at the
     * first node whose structure cannot be determined (unknown/modded parser property).
     */
    private void auditCommandTree(ByteBuf buf) {
        try {
            buf.skipBytes(1); // packet id
            var counts = new java.util.ArrayList<Integer>();
            int nodeCount = DefinedPacket.readVarInt(buf);
            java.util.Set<Integer> seen = new java.util.TreeSet<>();
            boolean drifted = false;
            for (int n = 0; n < nodeCount; n++) {
                if (!buf.isReadable()) {
                    drifted = true;
                    break;
                }
                int flags = buf.readByte() & 0xFF;
                int type = flags & 0x03;
                int children = DefinedPacket.readVarInt(buf);
                for (int c = 0; c < children; c++) {
                    DefinedPacket.readVarInt(buf);
                }
                if ((flags & 0x08) != 0) {
                    DefinedPacket.readVarInt(buf);
                }
                if (type == 1) {
                    DefinedPacket.readString(buf);
                } else if (type == 2) {
                    DefinedPacket.readString(buf);
                    int parser = DefinedPacket.readVarInt(buf);
                    seen.add(parser);
                    // Skip the vanilla parser properties; modded ones are unknown (stop auditing).
                    boolean known = skipParserProperties(buf, parser);
                    if (!known) {
                        drifted = true;
                        break;
                    }
                }
                if ((flags & 0x10) != 0) {
                    String ident = DefinedPacket.readString(buf);
                    if (!ident.isEmpty()) {
                        int sugg = DefinedPacket.readVarInt(buf);
                        for (int s = 0; s < sugg; s++) {
                            DefinedPacket.readString(buf);
                        }
                    }
                }
            }
            log.debug("{}: command tree audit: {} nodes, parser ids {}, snapshot ids {}, drift={}",
                    user.getUsername(), nodeCount, seen, serverArgumentTypes.keySet(), drifted);
        } catch (Exception e) {
            log.debug("{}: command tree audit aborted: {}", user.getUsername(), e.toString());
        }
    }

    /**
     * Skips one vanilla argument parser's properties using the exact parser-id → serializer mapping
     * from BungeeCord's {@code Commands.ArgumentRegistry}. The parser-id numbering shifts between
     * versions ({@code IDS_1_19_4} for 1.19.3-1.20.2, {@code IDS_1_20_3} for 1.20.3-1.20.4,
     * {@code IDS_1_20_5} for 1.20.5+). Returns false when the parser is modded/unknown, so callers
     * can abort walking.
     */
    private boolean skipParserProperties(ByteBuf buf, int parser) {
        int version = user.getProtocolVersion();

        // Range types: flags byte (0x01=min, 0x02=max) + optional bounds. The bound width depends
        // on the numeric type (float=4, double=8, integer=4, long=8). Stable ids across 1.19.3+.
        switch (parser) {
            case 1:   // brigadier:float → float bounds
            case 3: { // brigadier:integer → int bounds
                int flags = buf.readUnsignedByte();
                if ((flags & 0x01) != 0) {
                    buf.skipBytes(4);
                }
                if ((flags & 0x02) != 0) {
                    buf.skipBytes(4);
                }
                return true;
            }
            case 2:   // brigadier:double → double bounds
            case 4: { // brigadier:long → long bounds
                int flags = buf.readUnsignedByte();
                if ((flags & 0x01) != 0) {
                    buf.skipBytes(8);
                }
                if ((flags & 0x02) != 0) {
                    buf.skipBytes(8);
                }
                return true;
            }
            case 5: // brigadier:string → StringType (VarInt, 1 byte in practice)
                DefinedPacket.readVarInt(buf);
                return true;
            case 6: // minecraft:entity → single/multiple (byte)
                buf.skipBytes(1);
                return true;
            default:
                break;
        }

        // Parser ids that shift across versions.
        final int scoreHolderId;
        final int timeId;
        final int resourceStart;
        final int resourceEnd;
        final int maxVanillaId;
        if (version >= ProtocolConstants.MINECRAFT_1_20_5) {
            // 1.20.5+ (IDS_1_20_5)
            scoreHolderId = 30;
            timeId = 42;
            resourceStart = 43;
            resourceEnd = 46;
            maxVanillaId = 53;
        } else if (version >= ProtocolConstants.MINECRAFT_1_20_3) {
            // 1.20.3-1.20.4 (IDS_1_20_3)
            scoreHolderId = 30;
            timeId = 41;
            resourceStart = 42;
            resourceEnd = 45;
            maxVanillaId = 49;
        } else {
            // 1.19.3-1.20.2 (IDS_1_19_4)
            scoreHolderId = 29;
            timeId = 40;
            resourceStart = 41;
            resourceEnd = 44;
            maxVanillaId = 48;
        }

        if (parser == scoreHolderId) { // minecraft:score_holder → single/multiple (byte)
            buf.skipBytes(1);
            return true;
        }
        if (parser == timeId) { // minecraft:time → time unit (int, 4 bytes)
            buf.skipBytes(4);
            return true;
        }
        if (parser >= resourceStart && parser <= resourceEnd) {
            // minecraft:resource_or_tag / resource_or_tag_key / resource / resource_key → string
            DefinedPacket.readString(buf);
            return true;
        }

        // Every remaining vanilla parser carries no property (VOID).
        return parser >= 0 && parser <= maxVanillaId;
    }

    /**
     * Debug dump of one clientbound frame for 1.20.2+ players: the packet class (or raw id), the
     * front-end encoder/decoder protocol states and the packet id the encoder would assign in that
     * state. Shows exactly what the client receives and in which protocol phase — used to diagnose
     * config/play phase decode failures like "Failed to decode packet 'clientbound/minecraft:hello'".
     */
    private void dumpClientbound(Object msg) {
        try {
            var encoder = user.getChannel().pipeline().get(moe.koseirin.nyanruaineo.Minecraft.netty.PacketEncoder.class);
            var decoder = user.getChannel().pipeline().get(moe.koseirin.nyanruaineo.Minecraft.netty.PacketDecoder.class);
            String phase = "enc=" + (encoder == null ? "?" : encoder.getProtocol())
                    + " dec=" + (decoder == null ? "?" : decoder.getProtocol());
            if (msg instanceof DefinedPacket packet) {
                String willEncodeAs = "?";
                if (encoder != null) {
                    try {
                        willEncodeAs = "0x" + Integer.toHexString(encoder.getProtocol()
                                .getId(moe.koseirin.nyanruaineo.Minecraft.protocol.Direction.TO_CLIENT,
                                        packet.getClass(), user.getProtocolVersion()));
                    } catch (Exception ignored) {
                        willEncodeAs = "NOT-REGISTERED";
                    }
                }
                log.debug("{}: clientbound {} -> will-encode-as {} {}", user.getUsername(),
                        packet.getClass().getSimpleName(), willEncodeAs, phase);
            } else if (msg instanceof ByteBuf buf && buf.isReadable()) {
                int id = -1;
                try {
                    id = DefinedPacket.readVarInt(buf.duplicate());
                } catch (Exception ignored) {
                }
                int len = buf.readableBytes();
                String hex = java.util.HexFormat.of().formatHex(
                        moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket.toArray(
                                buf.duplicate()), 0, Math.min(64, len));
                // 1.21.1 declare_commands (0x11): dump the FULL payload so the command-tree
                // parser ids can be audited against the client's command_argument_type registry.
                // Config registry data (0x7/0xd in CONFIGURATION): full dump so the client-side
                // BuiltInRegistries.COMMAND_ARGUMENT_TYPE order can be reconstructed.
                boolean isCommands = id == 0x11 && user.getProtocolVersion() >= 766 && user.getProtocolVersion() <= 769;
                boolean isRegistryData = phase.contains("CONFIGURATION") && (id == 0x7 || id == 0xd);
                if (isCommands || isRegistryData) {
                    hex = java.util.HexFormat.of().formatHex(
                            moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket.toArray(buf.duplicate()));
                }
                if (isCommands) {
                    auditCommandTree(buf.duplicate());
                }
                log.debug("{}: clientbound raw frame id=0x{} ({} bytes) {} hex[{}..]{}", user.getUsername(),
                        Integer.toHexString(id), len, phase, hex,
                        isCommands ? " FULL-COMMANDS" : isRegistryData ? " FULL-REGISTRY" : "");
            }
        } catch (Exception ignored) {
            // Diagnostics must never break forwarding.
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        // Only act when this backend is still the current one (a server switch bumps the generation
        // and a new backend takes over).
        if (user.getServerGeneration() == generation) {
            // The backend dropped the player: fall back to the lobby — or kick when there is
            // nowhere to go. Idempotent: ServerConnector's closeFuture reports the same close, and
            // whichever runs first owns the client.
            if (!proxy.getPlayerTransferService().fallbackToLobby(user, server.getChannel())) {
                user.close();
            }
        }
    }

    /**
     * 判断这一帧是不是后端<b>正常关服</b>发出的游戏阶段 Disconnect。
     * <p>
     * Spigot 关服时对每个玩家发 {@code PlayerConnection.disconnect(settings.shutdown-message)}，
     * 默认文案就是 "Server closed"；识别它才能把玩家送回大厅，而不是让客户端自己断开。
     * 任何读取异常都按"不是关服包"处理——诊断永远不能影响转发。
     * （包级可见，便于回归测试直接调用。）
     */
    static boolean isBackendShutdownDisconnect(ByteBuf frame, int protocolVersion) {
        try {
            ByteBuf body = frame.duplicate();
            if (DefinedPacket.readVarInt(body) != ProtocolConstants.disconnectPacketId(protocolVersion)) {
                return false;
            }
            String reason = protocolVersion >= ProtocolConstants.MINECRAFT_1_20_3
                    ? ChatComponentUtils.readNbtComponent(body).toJSONString() // 1.20.3+ 匿名 NBT 组件
                    : DefinedPacket.readString(body);                          // 之前是 JSON 字符串
            String lower = reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("server closed")
                    || lower.contains("server_closed")
                    || lower.contains("server_shutdown")
                    || lower.contains("服务器已关闭");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        Channel userChannel = user.getChannel();
        if (userChannel != null) {
            // Stop reading from the client when the backend cannot keep up.
            userChannel.config().setAutoRead(ctx.channel().isWritable());
        }
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("DownstreamBridge error for {} from {}: {}", user.getUsername(),
                server.getHost(), cause.getMessage());
        ctx.close();
    }
}
