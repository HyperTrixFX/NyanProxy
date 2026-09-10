package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSONObject;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.TabListConfig;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolConstants;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoRemove;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoUpdate;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerListItem;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabListHeaderFooter;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;
import moe.koseirin.nyanruaineo.utils.System.SystemConfigCacheService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 这个类负责拦截客户端收到的 TabList（玩家列表）数据包，并根据数据库配置 {@code proxy.tablist}
 * 进行修改：包括替换列表的头部/底部文字（支持 1.8 到 1.20.2 版本），
 * 以及给每个玩家名字加上设定的前缀/后缀（支持 1.8 到 1.18.2 版本）。
 * 支持几个占位符：{% online %}（当前在线人数）、{% max %}（最大人数）——这两个只能用在头部/底部，
 * 还有 {% server %}——表示当前玩家所在的子服务器。对列表里的每个条目，会通过 UUID 查出该玩家在哪个服务器；
 * 如果查不到（比如不是代理管理的玩家），就回退使用查看者自己的服务器。如果功能被关闭，那就什么都不改，原样转发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TabListService {

    /** How long the cached config is reused before the next read of the in-memory cache. */
    private static final long CONFIG_CACHE_MILLIS = 5000;

    private final ProxyProperties properties;
    private final BackendServerManager backendServerManager;

    private volatile TabListConfig cachedConfig;
    private volatile long cachedConfigAt;

    private volatile List<BackendServer> cachedServers;
    private volatile long cachedServersAt;

    /**
     * The TabList config with a short in-memory cache of the parsed object. The underlying read
     * hits the {@link SystemConfigCacheService} map (loaded at startup, refreshed manually via
     * ReloadConfig or by the config edit flow), never the database.
     */
    private TabListConfig config() {
        long now = System.currentTimeMillis();
        TabListConfig config = cachedConfig;
        if (config == null || now - cachedConfigAt > CONFIG_CACHE_MILLIS) {
            config = properties.getTabListConfig();
            cachedConfig = config;
            cachedConfigAt = now;
        }
        return config;
    }

    /**
     * Whether the TabList rewriting feature is currently enabled. Callers on hot paths (for example
     * the online-count broadcast triggered by every join/quit) can use this to skip iterating all
     * players entirely when the feature is off.
     */
    public boolean isEnabled() {
        return config().isEnabled();
    }

    /** The configured server list with the same short cache (resolved per TabList entry). */
    private List<BackendServer> servers() {
        long now = System.currentTimeMillis();
        List<BackendServer> list = cachedServers;
        if (list == null || now - cachedServersAt > CONFIG_CACHE_MILLIS) {
            list = backendServerManager.listServers();
            cachedServers = list;
            cachedServersAt = now;
        }
        return list;
    }

    /**
     * Builds the configured header/footer packet for the proxy to push to a client when it enters
     * the play phase (join or server switch), mirroring BungeeCord's {@code setTabHeader} — the
     * proxy owns the TabList header/footer instead of waiting for the backend to send one. Returns
     * {@code null} when the feature is disabled, nothing is configured, or the version encodes
     * components as NBT (1.20.3+, not supported).
     */
    public TabListHeaderFooter buildHeaderFooter(UserConnection user, int onlineCount) {
        TabListConfig config = config();
        if (!config.isEnabled()) {
            return null;
        }
        String serverName = serverNameOf(user);
        JSONObject headerComp = config.getHeader() != null && !config.getHeader().isEmpty()
                ? buildLinesComponent(config.getHeader(), onlineCount, serverName) : null;
        JSONObject footerComp = config.getFooter() != null && !config.getFooter().isEmpty()
                ? buildLinesComponent(config.getFooter(), onlineCount, serverName) : null;
        if (headerComp == null && footerComp == null) {
            return null;
        }

        TabListHeaderFooter packet = new TabListHeaderFooter();
        if (user.getProtocolVersion() >= ProtocolConstants.MINECRAFT_1_20_3) {
            packet.setHeaderNbt(headerComp == null ? null : ChatComponentUtils.writeNbtComponentBytes(headerComp));
            packet.setFooterNbt(footerComp == null ? null : ChatComponentUtils.writeNbtComponentBytes(footerComp));
        } else {
            packet.setHeader(headerComp == null ? null : headerComp.toJSONString());
            packet.setFooter(footerComp == null ? null : footerComp.toJSONString());
        }
        return packet;
    }

    /**
     * Pushes the proxy-owned header/footer to one player (no-op when disabled or not configured).
     * The client clears the header/footer when it receives a JoinGame, so a server switch must
     * re-apply it; first joins get it via {@code playerJoined} → {@code refreshTabList}, but a
     * switch never re-enters that path.
     */
    public void pushHeaderFooter(UserConnection user, int onlineCount) {
        TabListHeaderFooter header = buildHeaderFooter(user, onlineCount);
        if (header != null) {
            user.sendPacket(header);
        }
    }

    /**
     * Replaces the header/footer components of the packet with the configured lines. The packet is
     * only touched when the feature is enabled and lines are configured; otherwise the backend's
     * own header/footer reaches the client unchanged.
     */
    public void applyHeaderFooter(TabListHeaderFooter packet, UserConnection user, int onlineCount) {
        TabListConfig config = config();
        if (!config.isEnabled()) {
            return;
        }
        String serverName = serverNameOf(user);
        boolean nbt = user.getProtocolVersion() >= ProtocolConstants.MINECRAFT_1_20_3;
        if (config.getHeader() != null && !config.getHeader().isEmpty()) {
            JSONObject component = buildLinesComponent(config.getHeader(), onlineCount, serverName);
            if (nbt) {
                packet.setHeaderNbt(ChatComponentUtils.writeNbtComponentBytes(component));
            } else {
                packet.setHeader(component.toJSONString());
            }
        }
        if (config.getFooter() != null && !config.getFooter().isEmpty()) {
            JSONObject component = buildLinesComponent(config.getFooter(), onlineCount, serverName);
            if (nbt) {
                packet.setFooterNbt(ChatComponentUtils.writeNbtComponentBytes(component));
            } else {
                packet.setFooter(component.toJSONString());
            }
        }
    }

    /**
     * Rewrites the display name of every added/renamed entry as {@code prefix + name + suffix},
     * resolving {@code %server%} per entry (the entry player's server, or the viewer's server when
     * the entry does not belong to a proxied player). Names of {@code UPDATE_DISPLAY_NAME} entries
     * are resolved from the names seen in previous {@code ADD_PLAYER} packets (tracked per player
     * on the {@link UserConnection}).
     */
    public void decorate(PlayerListItem packet, UserConnection user, Collection<UserConnection> onlineUsers) {
        // Always track added/removed entries so resetTabList can clear the TabList on a server
        // switch, independent of the decoration config (BungeeCord TabListHandler tracks entries
        // unconditionally for onServerChange).
        Map<UUID, String> names = user.getTabListNames();
        for (PlayerListItem.Item item : packet.getItems()) {
            switch (packet.getAction()) {
                case PlayerListItem.ACTION_ADD_PLAYER:
                    if (item.getUsername() != null) {
                        names.put(item.getUuid(), item.getUsername());
                    }
                    break;
                case PlayerListItem.ACTION_REMOVE_PLAYER:
                    names.remove(item.getUuid());
                    break;
                default:
                    break;
            }
        }

        TabListConfig config = config();
        if (!config.isEnabled()) {
            return;
        }
        String prefix = config.getPrefix() == null ? "" : config.getPrefix();
        String suffix = config.getSuffix() == null ? "" : config.getSuffix();
        if (prefix.isEmpty() && suffix.isEmpty()) {
            return;
        }

        String fallbackServer = serverNameOf(user);
        for (PlayerListItem.Item item : packet.getItems()) {
            switch (packet.getAction()) {
                case PlayerListItem.ACTION_ADD_PLAYER:
                case PlayerListItem.ACTION_UPDATE_DISPLAY_NAME:
                    decorate(item, names, prefix, suffix, fallbackServer, onlineUsers);
                    break;
                default:
                    break;
            }
        }
    }

    private void decorate(PlayerListItem.Item item, Map<UUID, String> names,
                          String prefix, String suffix, String fallbackServer,
                          Collection<UserConnection> onlineUsers) {
        String name = item.getUsername() != null ? item.getUsername() : names.get(item.getUuid());
        if (name == null || name.isEmpty()) {
            return;
        }
        String serverName = serverNameOf(item.getUuid(), onlineUsers);
        if (serverName == null) {
            serverName = fallbackServer;
        }
        if (serverName == null) {
            serverName = "?";
        }

        String resolvedPrefix = prefix.replace("%server%", serverName);
        String resolvedSuffix = suffix.replace("%server%", serverName);
        item.setDisplayName(ChatComponentUtils.component(resolvedPrefix + name + resolvedSuffix).toJSONString());
    }

    /**
     * 1.19.3+ variant of {@link #decorate(PlayerListItem, UserConnection, Collection)}: rewrites
     * the display names of a {@link PlayerInfoUpdate} packet and adds the
     * {@code UPDATE_DISPLAY_NAME} action bit when names were decorated but the packet did not
     * carry display names yet (the bit applies to every entry of the packet).
     */
    public void decorateUpdate(PlayerInfoUpdate packet, UserConnection user, Collection<UserConnection> onlineUsers) {
        // Always track ADD_PLAYER names so resetTabList can clear the TabList on a server switch,
        // independent of the decoration config (BungeeCord TabListHandler tracks entries
        // unconditionally for onServerChange).
        Map<UUID, String> names = user.getTabListNames();
        if (packet.hasAction(PlayerInfoUpdate.ACTION_ADD_PLAYER)) {
            for (PlayerInfoUpdate.Item item : packet.getItems()) {
                if (item.getUsername() != null) {
                    names.put(item.getUuid(), item.getUsername());
                }
            }
        }

        TabListConfig config = config();
        if (!config.isEnabled()) {
            return;
        }
        String prefix = config.getPrefix() == null ? "" : config.getPrefix();
        String suffix = config.getSuffix() == null ? "" : config.getSuffix();
        if (prefix.isEmpty() && suffix.isEmpty()) {
            return;
        }

        String fallbackServer = serverNameOf(user);
        boolean decoratedAny = false;
        for (PlayerInfoUpdate.Item item : packet.getItems()) {
            boolean hasAdd = packet.hasAction(PlayerInfoUpdate.ACTION_ADD_PLAYER);
            boolean hasDisplayName = packet.hasAction(PlayerInfoUpdate.ACTION_UPDATE_DISPLAY_NAME);
            if (!hasAdd && !hasDisplayName) {
                continue;
            }
            String name = item.getUsername() != null ? item.getUsername() : names.get(item.getUuid());
            if (name == null || name.isEmpty()) {
                continue;
            }
            String serverName = serverNameOf(item.getUuid(), onlineUsers);
            if (serverName == null) {
                serverName = fallbackServer;
            }
            if (serverName == null) {
                serverName = "?";
            }
            String resolvedPrefix = prefix.replace("%server%", serverName);
            String resolvedSuffix = suffix.replace("%server%", serverName);
            if (user.getProtocolVersion() >= ProtocolConstants.MINECRAFT_1_20_3) {
                item.setDisplayNameNbt(ChatComponentUtils.writeNbtComponentBytes(
                        ChatComponentUtils.component(resolvedPrefix + name + resolvedSuffix)));
            } else {
                item.setDisplayName(ChatComponentUtils.component(resolvedPrefix + name + resolvedSuffix).toJSONString());
            }
            decoratedAny = true;
        }
        if (decoratedAny && !packet.hasAction(PlayerInfoUpdate.ACTION_UPDATE_DISPLAY_NAME)) {
            packet.setActions(packet.getActions() | (1 << PlayerInfoUpdate.ACTION_UPDATE_DISPLAY_NAME));
        }
    }

    /** Forgets the TabList names of the players removed by a 1.19.3+ remove packet. */
    public void removeEntries(PlayerInfoRemove packet, UserConnection user) {
        Map<UUID, String> names = user.getTabListNames();
        for (UUID uuid : packet.getUuids()) {
            names.remove(uuid);
        }
    }

    /**
     * BungeeCord {@code TabListHandler.onServerChange} parity: when the player switches servers,
     * removal packets are sent for every entry the old server added, so the client's TabList can
     * never carry stale entries into the new world. (1.19-1.19.2 player info packets are not
     * registered, so those versions rely on the JoinGame reset instead.)
     */
    public void resetTabList(UserConnection user) {
        Map<UUID, String> names = user.getTabListNames();
        if (names.isEmpty()) {
            return;
        }
        int version = user.getProtocolVersion();
        List<UUID> uuids = new ArrayList<>(names.keySet());
        if (version < 759) {
            List<PlayerListItem.Item> items = new ArrayList<>(uuids.size());
            for (UUID uuid : uuids) {
                PlayerListItem.Item item = new PlayerListItem.Item();
                item.setUuid(uuid);
                items.add(item);
            }
            user.sendPacket(new PlayerListItem(PlayerListItem.ACTION_REMOVE_PLAYER, items));
        } else if (version >= 761) {
            user.sendPacket(new PlayerInfoRemove(uuids));
        }
        names.clear();
    }

    /** Builds the configured lines as a chat-component tree (placeholders already resolved). */
    private JSONObject buildLinesComponent(List<String> lines, int onlineCount, String serverName) {
        String joined = String.join("\n", lines);
        joined = joined.replace("%online%", String.valueOf(onlineCount));
        joined = joined.replace("%max%", String.valueOf(properties.getMaxPlayers()));
        if (serverName != null) {
            joined = joined.replace("%server%", serverName);
        }
        return ChatComponentUtils.component(joined);
    }

    /** The configured server name of the connection, or {@code host:port} when not configured. */
    private String serverNameOf(UserConnection user) {
        ServerConnection server = user.getServer();
        if (server == null) {
            return "?";
        }
        for (BackendServer backend : servers()) {
            if (backend.getHost() != null && backend.getHost().equalsIgnoreCase(server.getHost())
                    && backend.getPort() == server.getPort()) {
                return backend.getUid();
            }
        }
        return "Unknown Continent";
    }

    /** The server name a UUID's player is currently on, or {@code null} when not a proxied player. */
    private String serverNameOf(UUID uuid, Collection<UserConnection> onlineUsers) {
        for (UserConnection user : onlineUsers) {
            if (uuid.equals(user.getUuid())) {
                if (user.getServer() == null) {
                    return null;
                }
                return serverNameOf(user);
            }
        }
        return null;
    }
}
