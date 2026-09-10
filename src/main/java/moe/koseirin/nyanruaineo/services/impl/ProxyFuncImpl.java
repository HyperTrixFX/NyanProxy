package moe.koseirin.nyanruaineo.services.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BanMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.FirewallConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.KickMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.MotdConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.TabListConfig;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.service.BackendServerManager;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerKickService;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerQueryService;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerTransferService;
import moe.koseirin.nyanruaineo.dto.BackendServerStatusDTO;
import moe.koseirin.nyanruaineo.dto.ProxyConfigDTO;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.System.SystemConfigCacheService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/*
 * @author KoseiRin_
 * awa
 */

/**
 * 代理后端服务器的业务逻辑（增删改查）。HTTP 端点位于
 * {@code moe.koseirin.nyanruaineo.server.V3Contorller.ProxyController}，鉴权由
 * {@code PermissionService} 负责。
 */
@Service
@RequiredArgsConstructor
public class ProxyFuncImpl {

    private final MinecraftProxy proxy;
    private final PlayerQueryService playerQueryService;
    private final SystemConfigCacheService cacheService;
    private final BackendServerManager backendServerManager;
    private final PlayerTransferService playerTransferService;
    private final PlayerKickService playerKickService;
    private final Respond respond;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    private static final String KEY_BACKEND_SERVERS = "proxy.backend.servers";

    // 可在管理面板编辑的代理配置项（backend.servers 由 /servers 专用接口管理，不在此列）。
    private static final String KEY_PORT = "proxy.port";
    private static final String KEY_MAX_PLAYERS = "proxy.maxPlayers";
    private static final String KEY_NAME = "proxy.name";
    private static final String KEY_ONLINE_MODE = "proxy.online-mode";
    private static final String KEY_IP_FORWARD = "proxy.ip-forward";
    private static final String KEY_FORGE_SUPPORT = "proxy.forge-support";
    private static final String KEY_MOTD = "proxy.motd";
    private static final String KEY_TABLIST = "proxy.tablist";
    private static final String KEY_FIREWALL = "proxy.firewall";
    private static final String KEY_KICK_MESSAGE = "proxy.kick-message";
    private static final String KEY_BAN_MESSAGE = "proxy.ban-message";

    private static final List<String> CONFIG_KEYS = List.of(
            KEY_PORT, KEY_MAX_PLAYERS, KEY_NAME,
            KEY_ONLINE_MODE, KEY_IP_FORWARD, KEY_FORGE_SUPPORT,
            KEY_MOTD, KEY_TABLIST, KEY_FIREWALL, KEY_KICK_MESSAGE, KEY_BAN_MESSAGE);

    private static final int MAX_CONFIG_VALUE_LENGTH = 800;

    @Transactional
    public List<BackendServerStatusDTO> getAllServers() {
        List<BackendServer> servers;
        readLock.lock();
        try {
            JSONObject config = readConfig();
            JSONArray serverArray = config.getJSONArray("server_list");
            if (serverArray == null) {
                return new ArrayList<>();
            }
            servers = serverArray.toList(BackendServer.class);
        } finally {
            readLock.unlock();
        }

        // 在线探测 / 玩家统计在锁外执行：TCP 探测可能阻塞，避免拖慢写操作。
        List<BackendServerStatusDTO> result = new ArrayList<>(servers.size());
        for (BackendServer server : servers) {
            BackendServerStatusDTO dto = new BackendServerStatusDTO();
            dto.setUid(server.getUid());
            dto.setPriority(server.getPriority());
            dto.setName(server.getName());
            dto.setHost(server.getHost());
            dto.setPort(server.getPort());
            List<BackendServerStatusDTO.PlayerEntry> players = playersOn(server);
            dto.setPlayers(players);
            dto.setOnlineCount(players.size());
            dto.setOnline(backendServerManager.isOnline(server));
            result.add(dto);
        }
        return result;
    }

    /** 当前连接到指定后端的玩家列表（按 host+port 匹配）。 */
    private List<BackendServerStatusDTO.PlayerEntry> playersOn(BackendServer server) {
        List<BackendServerStatusDTO.PlayerEntry> players = new ArrayList<>();
        if (server == null || server.getHost() == null || server.getHost().isBlank() || server.getPort() <= 0) {
            return players;
        }
        var onlineUsers = proxy.getOnlineUsers();
        if (onlineUsers == null) {
            return players;
        }
        for (UserConnection user : onlineUsers) {
            ServerConnection conn = user.getServer();
            if (conn != null && server.getHost().equalsIgnoreCase(conn.getHost()) && server.getPort() == conn.getPort()) {
                players.add(new BackendServerStatusDTO.PlayerEntry(user.getUsername(), user.getUuid()));
            }
        }
        return players;
    }

    /** 将在线玩家转移到指定子服务器（转移前检查玩家状态与目的服务器状态）。 */
    public ResponseEntity<?> transferPlayer(String player, String targetServer) {
        if (player == null || player.isBlank()) {
            return badRequest("Player is required");
        }
        if (targetServer == null || targetServer.isBlank()) {
            return badRequest("Target server is required");
        }

        // 玩家状态检查：在线且连接活跃
        UserConnection user = resolvePlayer(player);
        if (user == null) {
            return notFound("Player not online: " + player);
        }
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            return conflict("Player connection is not active");
        }

        // 目的服务器检查：存在且地址可用
        BackendServer target = resolveServer(targetServer);
        if (target == null) {
            return notFound("Unknown server: " + targetServer);
        }
        if (target.getHost() == null || target.getHost().isBlank() || target.getPort() <= 0) {
            return conflict("Target server has no usable address");
        }
        if (!backendServerManager.isOnline(target)) {
            return conflict("Target server is offline: " + serverDisplayName(target));
        }

        // 已在目标服务器则无需转移
        ServerConnection current = user.getServer();
        if (current != null && !current.isClosed()
                && target.getHost().equalsIgnoreCase(current.getHost())
                && target.getPort() == current.getPort()) {
            return conflict("Player is already connected to " + serverDisplayName(target));
        }

        boolean started = playerTransferService.transfer(user, target);
        if (!started) {
            return conflict("Transfer could not start (player already switching or disconnected)");
        }
        return ok("Transferring " + user.getUsername() + " to " + serverDisplayName(target));
    }

    /** 踢出在线玩家，reason 可选（缺省使用默认原因）。 */
    public ResponseEntity<?> kickPlayer(String player, String reason) {
        if (player == null || player.isBlank()) {
            return badRequest("Player is required");
        }

        // 玩家状态检查：在线且连接活跃
        UserConnection user = resolvePlayer(player);
        if (user == null) {
            return notFound("Player not online: " + player);
        }
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            return conflict("Player connection is not active");
        }

        String actualReason = (reason == null || reason.isBlank()) ? "Kicked by an administrator" : reason.trim();
        if (actualReason.length() > 256) {
            return badRequest("Reason too long (max 256)");
        }
        playerKickService.kick(user, actualReason, null);
        return ok("Kicked " + user.getUsername());
    }

    /** 列出可在管理面板编辑的代理配置项（键 + 原始值 + 类型提示）。 */
    public List<ProxyConfigDTO> getProxyConfigs() {
        List<ProxyConfigDTO> result = new ArrayList<>(CONFIG_KEYS.size());
        for (String key : CONFIG_KEYS) {
            result.add(new ProxyConfigDTO(key, cacheService.getConfig(key), configType(key)));
        }
        return result;
    }

    /** 批量更新代理配置：逐项严格校验，全部通过后写库并热重载缓存。 */
    public ResponseEntity<?> updateProxyConfigs(Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return badRequest("No config entries provided");
        }

        List<String> updatedKeys = new ArrayList<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank()) {
                return badRequest("Config key is required");
            }
            if (!isKnownKey(key)) {
                return badRequest("Unknown config key: " + key);
            }
            String error = validateValue(key, value);
            if (error != null) {
                return badRequest("Invalid value for " + key + ": " + error);
            }
            cacheService.updateConfig(key, value);
            updatedKeys.add(key);
        }

        // 热重载：让 ProxyProperties 等读取方立即使用新值（部分项如 proxy.port 需重启才真正生效）。
        cacheService.loadConfigs();
        return ok("Updated configs: " + String.join(", ", updatedKeys));
    }

    private boolean isKnownKey(String key) {
        return CONFIG_KEYS.contains(key);
    }

    private String configType(String key) {
        return switch (key) {
            case KEY_PORT, KEY_MAX_PLAYERS -> "int";
            case KEY_ONLINE_MODE, KEY_IP_FORWARD, KEY_FORGE_SUPPORT -> "boolean";
            case KEY_NAME -> "string";
            case KEY_MOTD, KEY_TABLIST, KEY_FIREWALL, KEY_KICK_MESSAGE, KEY_BAN_MESSAGE -> "json";
            default -> "string";
        };
    }

    /** 校验单个配置值，返回错误描述；null 表示通过。 */
    private String validateValue(String key, String value) {
        if (value == null) {
            return "value is required";
        }
        if (value.length() > MAX_CONFIG_VALUE_LENGTH) {
            return "value too long (max " + MAX_CONFIG_VALUE_LENGTH + ")";
        }
        return switch (key) {
            case KEY_PORT -> validateInt(value, 1, 65535);
            case KEY_MAX_PLAYERS -> validateInt(value, 0, Integer.MAX_VALUE);
            case KEY_ONLINE_MODE, KEY_IP_FORWARD, KEY_FORGE_SUPPORT -> validateBoolean(value);
            case KEY_NAME -> value.isBlank() ? "must not be blank" : null;
            case KEY_MOTD -> validateJson(value, MotdConfig.class);
            case KEY_TABLIST -> validateJson(value, TabListConfig.class);
            case KEY_FIREWALL -> validateJson(value, FirewallConfig.class);
            case KEY_KICK_MESSAGE -> validateJson(value, KickMessageConfig.class);
            case KEY_BAN_MESSAGE -> validateJson(value, BanMessageConfig.class);
            default -> "unknown key";
        };
    }

    private String validateInt(String value, int min, int max) {
        try {
            int n = Integer.parseInt(value.trim());
            if (n < min || n > max) {
                return "must be between " + min + " and " + max;
            }
            return null;
        } catch (NumberFormatException e) {
            return "must be an integer";
        }
    }

    private String validateBoolean(String value) {
        String v = value.trim();
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return null;
        }
        return "must be true or false";
    }

    private <T> String validateJson(String value, Class<T> clazz) {
        if (value.isBlank()) {
            return "must not be blank";
        }
        try {
            T parsed = JSON.parseObject(value, clazz);
            return parsed == null ? "invalid JSON (parsed to null)" : null;
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
    }

    /** 按用户名（忽略大小写）或 UUID 解析在线玩家，均未命中返回 null。 */
    private UserConnection resolvePlayer(String player) {
        if (player == null || player.isBlank()) {
            return null;
        }
        UserConnection user = playerQueryService.getUserConnection(player);
        if (user != null) {
            return user;
        }
        try {
            return playerQueryService.getUserConnectionByUUID(player);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 按 uid（精确）或服务器名（忽略大小写）解析后端服务器，均未命中返回 null。 */
    private BackendServer resolveServer(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        List<BackendServer> servers = backendServerManager.listServers();
        if (servers == null) {
            return null;
        }
        for (BackendServer server : servers) {
            if (server == null) {
                continue;
            }
            if (id.equals(server.getUid())) {
                return server;
            }
            if (server.getName() != null && server.getName().equalsIgnoreCase(id)) {
                return server;
            }
        }
        return null;
    }

    /** 服务器的展示名，避免 null 拼进消息里。 */
    private String serverDisplayName(BackendServer server) {
        if (server == null) {
            return "?";
        }
        if (server.getName() != null && !server.getName().isBlank()) {
            return server.getName();
        }
        if (server.getUid() != null && !server.getUid().isBlank()) {
            return server.getUid();
        }
        return (server.getHost() == null ? "?" : server.getHost()) + ":" + server.getPort();
    }

    private ResponseEntity<?> ok(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", message, "timestamp", LocalDateTime.now());
    }

    private ResponseEntity<?> badRequest(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 400, "message", message, "timestamp", LocalDateTime.now());
    }

    private ResponseEntity<?> notFound(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 404, "message", message, "timestamp", LocalDateTime.now());
    }

    private ResponseEntity<?> conflict(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 409, "message", message, "timestamp", LocalDateTime.now());
    }

    @Transactional
    public ResponseEntity<?> addServer(BackendServer newServer) {
        writeLock.lock();
        try {
            if (!validateServer(newServer)) {
                return ResponseEntity.badRequest().build();
            }

            newServer.setUid(UUID.randomUUID().toString());
            JSONObject config = readConfig();
            JSONArray serverArray = config.getJSONArray("server_list");
            if (serverArray == null) {
                serverArray = new JSONArray();
                config.put("server_list", serverArray);
            }
            serverArray.add(newServer);
            saveConfig(config);
            return ResponseEntity.ok().body(config);
        } finally {
            writeLock.unlock();
        }
    }

    @Transactional
    public ResponseEntity<?> removeServer(String uid) {
        writeLock.lock();
        try {
            JSONObject config = readConfig();
            JSONArray serverArray = config.getJSONArray("server_list");
            if (serverArray != null) {
                serverArray.removeIf(obj -> {
                    JSONObject server = (JSONObject) obj;
                    return uid.equals(server.getString("uid"));
                });
            }
            saveConfig(config);
            return ResponseEntity.ok().body(config);
        } finally {
            writeLock.unlock();
        }
    }

    @Transactional
    public ResponseEntity<?> updateServer(String uid, BackendServer updatedServer) {
        writeLock.lock();
        try {
            if (!validateServer(updatedServer)) {
                return ResponseEntity.badRequest().build();
            }
            JSONObject config = readConfig();
            JSONArray serverArray = config.getJSONArray("server_list");
            if (serverArray != null) {
                for (int i = 0; i < serverArray.size(); i++) {
                    JSONObject server = serverArray.getJSONObject(i);
                    if (uid.equals(server.getString("uid"))) {
                        server.put("name", updatedServer.getName());
                        server.put("host", updatedServer.getHost());
                        server.put("port", updatedServer.getPort());
                        server.put("priority", updatedServer.getPriority());
                        break;
                    }
                }
            }
            saveConfig(config);
            return ResponseEntity.ok().body(config);
        } finally {
            writeLock.unlock();
        }
    }

    public JSONObject readConfig() {
        String json = cacheService.getConfig(KEY_BACKEND_SERVERS);
        return JSONObject.parseObject(json);
    }

    public void saveConfig(JSONObject config) {
        cacheService.updateConfig(KEY_BACKEND_SERVERS, config.toJSONString());
        cacheService.loadConfigs();
    }

    private boolean validateServer(BackendServer server) {
        if (server == null) {
            return false;
        }
        if (server.getHost() == null || server.getHost().isBlank()) {
            return false;
        }
        if (server.getPort() < 1 || server.getPort() > 65535) {
            return false;
        }
        if (server.getName() == null || server.getName().isBlank()) {
            return false;
        }
        return server.getPriority() >= 0;
    }
}
