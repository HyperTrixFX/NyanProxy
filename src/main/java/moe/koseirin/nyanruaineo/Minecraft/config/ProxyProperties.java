package moe.koseirin.nyanruaineo.Minecraft.config;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BanMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.FirewallConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.KickMessageConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.MotdConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.TabListConfig;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.ServerListConfig;
import moe.koseirin.nyanruaineo.utils.System.SystemConfigCacheService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
public class ProxyProperties {
    private static final String KEY_PORT = "proxy.port";
    private static final String KEY_BACKEND_SERVERS = "proxy.backend.servers";
    private static final String KEY_MOTD = "proxy.motd";
    private static final String KEY_TABLIST = "proxy.tablist";
    private static final String KEY_FIREWALL = "proxy.firewall";
    private static final String KEY_KICK_MESSAGE = "proxy.kick-message";
    private static final String KEY_BAN_MESSAGE = "proxy.ban-message";
    private static final String KEY_MAX_PLAYERS = "proxy.maxPlayers";
    private static final String KEY_ONLINE_MODE = "proxy.online-mode";
    private static final String KEY_IP_FORWARD = "proxy.ip-forward";
    private static final String KEY_NAME = "proxy.name";
    private static final String KEY_FORGE_SUPPORT = "proxy.forge-support";

    private final SystemConfigCacheService cacheService;

    /** 旧格式踢出模板 → 独立封禁模板的一次性迁移标记。 */
    private volatile boolean migrationChecked = false;

    public ProxyProperties(SystemConfigCacheService cacheService) {
        this.cacheService = cacheService;
    }

    public int getPort() {
        String val = cacheService.getConfig(KEY_PORT);
        if (val == null){
            cacheService.addConfig(KEY_PORT,"25565");
            return 25565;
        }
        return Integer.parseInt(val);
    }

    /**
     * 从 {@code proxy.backend.servers} 读取子服务器列表。存储的值是一个
     * {@link ServerListConfig} 对象：
     *
     * <pre>
     * {
     * "default_server": "lobby",
     * "server_list": [
     * { "uid": "lobby-001", "priority": 1, "name": "lobby", "host": "localhost", "port": 25566 },
     * { "uid": "survival-001", "priority": 2, "name": "survival", "host": "localhost", "port": 25567 }
     * ]
     * }
     * </pre>
     *
     * 该值从内存中的 {@link SystemConfigCacheService} 读取（在启动时加载，
     * 由配置编辑流程和手动 {@code ReloadConfig} 刷新保持最新——这里不会查询数据库）。
     * 缺失的键会写回默认配置；旧的 JSON 数组格式会自动迁移。不再使用旧的单后台
     * {@code proxy.backend.host/port} 键。
     */
    public ServerListConfig getServerListConfig() {
        String val = cacheService.getConfig(KEY_BACKEND_SERVERS);

        if (val == null || val.isBlank()) {
            ServerListConfig defaultConfig = new ServerListConfig();
            defaultConfig.setDefaultServer("lobby");
            List<BackendServer> servers = new ArrayList<>();
            servers.add(new BackendServer("lobby-001", 1, "lobby", "localhost", 25566));
            servers.add(new BackendServer("survival-001", 2, "survival", "localhost", 25567));
            defaultConfig.setServerList(servers);
            try {
                cacheService.updateConfig(KEY_BACKEND_SERVERS, JSON.toJSONString(defaultConfig));
            } catch (Exception e) {
                try {
                    cacheService.addConfig(KEY_BACKEND_SERVERS, JSON.toJSONString(defaultConfig));
                } catch (Exception ignored) {
                    // Already present or no transaction — the default is returned regardless.
                }
            }
            return defaultConfig;
        }

        try {
            String trimmed = val.trim();
            if (trimmed.startsWith("[")) {
                // Legacy JSON-array format: migrate to the new object format.
                List<BackendServer> list = JSON.parseArray(trimmed, BackendServer.class);
                ServerListConfig migrated = new ServerListConfig();
                migrated.setServerList(list == null ? new ArrayList<>() : list);
                List<BackendServer> migratedList = migrated.getServerList();
                if (migratedList.isEmpty()) {
                    migrated.setDefaultServer(null);
                } else {
                    migratedList.sort(Comparator.comparingInt(BackendServer::getPriority));
                    migrated.setDefaultServer(migratedList.getFirst().getName());
                }
                try {
                    cacheService.updateConfig(KEY_BACKEND_SERVERS, JSON.toJSONString(migrated));
                } catch (Exception ignored) {
                }
                log.info("Migrated {} to the new server list format: {}", KEY_BACKEND_SERVERS, migrated);
                return migrated;
            }
            ServerListConfig config = JSON.parseObject(trimmed, ServerListConfig.class);
            if (config != null && config.getServerList() == null) {
                // Some editors store the object with camelCase keys; accept them as aliases so the
                // server list is never silently lost.
                JSONObject raw = JSON.parseObject(trimmed);
                if (raw != null) {
                    List<BackendServer> list = raw.getList("serverList", BackendServer.class);
                    if (list == null) {
                        list = raw.getList("server_list", BackendServer.class);
                    }
                    if (list != null) {
                        config.setServerList(list);
                    }
                    String def = raw.getString("defaultServer");
                    if (def == null) {
                        def = raw.getString("default_server");
                    }
                    config.setDefaultServer(def);
                }
            }
            if (config == null) {
                log.error("Parsed {} to null; the server list will be empty (value: {})", KEY_BACKEND_SERVERS, val);
                return new ServerListConfig();
            }
            if (config.getServerList() == null) {
                log.warn("{} contains no server_list; the /server list will be empty (value: {})",
                        KEY_BACKEND_SERVERS, val);
                config.setServerList(new ArrayList<>());
            }
            return config;
        } catch (Exception e) {
            log.error("Failed to parse {} (value: {}): {}", KEY_BACKEND_SERVERS, val, e.getMessage());
            return new ServerListConfig();
        }
    }

    /**
     * 配置的子服务器列表。没有具体主机/端口的条目会被跳过并显示警告，
     * 因此错误配置的条目不会破坏整个列表。
     */
    public List<BackendServer> getBackendServers() {
        ServerListConfig config = getServerListConfig();
        List<BackendServer> servers = config.getServerList();
        if (servers == null) {
            return List.of();
        }
        List<BackendServer> valid = new ArrayList<>();
        for (BackendServer server : servers) {
            if (server.getHost() == null || server.getHost().isBlank() || server.getPort() <= 0) {
                log.warn("Skipping server entry with missing host/port: {}", server);
                continue;
            }
            valid.add(server);
        }
        return valid;
    }
    public MotdConfig getMotdConfig() {
        String val = cacheService.getConfig(KEY_MOTD);
        if (val == null) {
            MotdConfig defaultConfig = new MotdConfig();
            defaultConfig.setLines(Arrays.asList(
                    "§a§lMinecraft Proxy",
                    "§7Powered by Spring Boot"
            ));
            defaultConfig.setFakePlayersEnabled(false);
            defaultConfig.setFakePlayersMin(10);
            defaultConfig.setFakePlayersMax(50);
            defaultConfig.setFakePlayersIncrement(1);
            defaultConfig.setMaxPlayers(100);
            defaultConfig.setVersionName("Minecraft 1.20.1");
            defaultConfig.setProtocolVersion(763);
            defaultConfig.setHoverLines(Arrays.asList(
                    "§6Welcome to our proxy!",
                    "§7Online: §a%online%",
                    "§7Fake: §e%fake_online%"
            ));

            String json = JSON.toJSONString(defaultConfig);
            cacheService.addConfig(KEY_MOTD, json);
            return defaultConfig;
        }

        return JSON.parseObject(val, MotdConfig.class);
    }

    public int getMaxPlayers() {
        String val = cacheService.getConfig(KEY_MAX_PLAYERS);
        if (val == null){
            cacheService.addConfig(KEY_MAX_PLAYERS,"100");
            return 100;
        }
        return Integer.parseInt(val);
    }

    /**
     * 从 {@code proxy.tablist} 读取 TabList 拦截配置：
     *
     * <pre>
     * {
     * "enabled": true,
     * "header": ["§6§lNyanID 服务器群", "§7在线: §a%online%"],
     * "footer": ["§7跨服代理 · §b/server 切换"],
     * "prefix": "§7[§a玩家§7] §f",
     * "suffix": ""
     * }
     * </pre>
     *
     * 运行时修改配置后刷新缓存服务即可热重载；
     * 缺失的键会将（禁用的）默认值写回缓存。头部/底部行支持 {@code %online%} / {@code %max%} 占位符；前缀/后缀会包裹 TabList 中显示的每个玩家名称（1.8-1.18.2 客户端）。
     */
    public TabListConfig getTabListConfig() {
        String val = cacheService.getConfig(KEY_TABLIST);

        if (val == null || val.isBlank()) {
            TabListConfig defaultConfig = new TabListConfig();
            defaultConfig.setEnabled(false);
            defaultConfig.setHeader(Arrays.asList("§6§lNyanID 服务器群", "§7在线: §a%online%"));
            defaultConfig.setFooter(Arrays.asList("§7跨服代理 · §b/server 切换"));
            defaultConfig.setPrefix("");
            defaultConfig.setSuffix(" §8[%server%]");
            try {
                cacheService.updateConfig(KEY_TABLIST, JSON.toJSONString(defaultConfig));
            } catch (Exception e) {
                try {
                    cacheService.addConfig(KEY_TABLIST, JSON.toJSONString(defaultConfig));
                } catch (Exception ignored) {
                    // ignored
                }
            }
            return defaultConfig;
        }

        try {
            TabListConfig config = JSON.parseObject(val.trim(), TabListConfig.class);
            if (config == null) {
                log.error("Parsed {} to null; TabList interception disabled (value: {})", KEY_TABLIST, val);
                return new TabListConfig();
            }
            return config;
        } catch (Exception e) {
            log.error("Failed to parse {} (value: {}): {}", KEY_TABLIST, val, e.getMessage());
            return new TabListConfig();
        }
    }

    /**
     * 从 {@code proxy.firewall} 读取连接防火墙配置：
     *
     * <pre>
     * {
     * "enabled": true,
     * "maxConnectionsPerSecond": 3,
     * "maxConcurrentPerIp": 5,
     * "maxLoginAttemptsPerMinute": 10,
     * "maxLoginFailuresPerMinute": 5,
     * "banDurationSeconds": 300
     * }
     * </pre>
     *
     * 缺失时写回默认值；运行时修改配置后刷新缓存即可热重载。
     */
    public FirewallConfig getFirewallConfig() {
        String val = cacheService.getConfig(KEY_FIREWALL);

        if (val == null || val.isBlank()) {
            FirewallConfig defaultConfig = new FirewallConfig();
            defaultConfig.setEnabled(true);
            defaultConfig.setMaxConnectionsPerSecond(5);
            defaultConfig.setMaxConcurrentPerIp(12);
            defaultConfig.setMaxLoginAttemptsPerMinute(20);
            defaultConfig.setMaxLoginFailuresPerMinute(6);
            defaultConfig.setBanDurationSeconds(600);
            try {
                cacheService.updateConfig(KEY_FIREWALL, JSON.toJSONString(defaultConfig));
            } catch (Exception e) {
                try {
                    cacheService.addConfig(KEY_FIREWALL, JSON.toJSONString(defaultConfig));
                } catch (Exception ignored) {
                    // ignored
                }
            }
            return defaultConfig;
        }

        try {
            FirewallConfig config = JSON.parseObject(val.trim(), FirewallConfig.class);
            if (config == null) {
                log.error("Parsed {} to null; firewall disabled (value: {})", KEY_FIREWALL, val);
                return new FirewallConfig();
            }
            return config;
        } catch (Exception e) {
            log.error("Failed to parse {} (value: {}): {}", KEY_FIREWALL, val, e.getMessage());
            return new FirewallConfig();
        }
    }

    /**
     * 从 {@code proxy.kick-message} 读取普通踢出屏幕模板（与封禁模板 {@code proxy.ban-message} 分离）。
     *
     * <pre>
     * {
     *   "enabled": true,
     *   "kick_message_base": "&cYou have been kicked!\n&7Reason: &f$reason\n&7Kick ID: &f$kickId"
     * }
     * </pre>
     *
     * 模板支持 {@code &} 颜色代码、{@code n}（或 {@code |}）换行，占位符
     * {@code $playerName} / {@code $reason} / {@code $kickId}。
     * 旧版本把封禁模板也放在这个键下（字段名 {@code banned_message_base}），首次读取时会自动迁移到
     * {@code proxy.ban-message} 并回写新的踢出默认模板。
     */
    public KickMessageConfig getKickMessageConfig() {
        ensureConfigsMigrated();
        KickMessageConfig config = parseConfig(KEY_KICK_MESSAGE, KickMessageConfig.class);
        if (config == null) {
            config = defaultKickConfig();
            writeConfig(KEY_KICK_MESSAGE, config);
        }
        return config;
    }

    /**
     * 从 {@code proxy.ban-message} 读取封禁屏幕模板。
     *
     * <pre>
     * {
     *   "enabled": true,
     *   "banned_message_base": "&5&l... \n&b&l»&f&lPlayer: &4$playerName\n...$banId...$expireTime..."
     * }
     * </pre>
     *
     * 占位符 {@code $playerName} / {@code $reason} / {@code $banId} / {@code $expireTime}
     * （永久封禁时 {@code $expireTime} 显示「永久」）。
     */
    public BanMessageConfig getBanMessageConfig() {
        ensureConfigsMigrated();
        BanMessageConfig config = parseConfig(KEY_BAN_MESSAGE, BanMessageConfig.class);
        if (config == null) {
            config = defaultBanConfig();
            writeConfig(KEY_BAN_MESSAGE, config);
        }
        return config;
    }

    private KickMessageConfig defaultKickConfig() {
        KickMessageConfig config = new KickMessageConfig();
        config.setEnabled(true);
        config.setKickMessageBase(
                "&c&lYou have been kicked from the proxy!\n"
                        + "&7Reason: &f$reason\n"
                        + "&7Kick ID: &f$kickId");
        return config;
    }

    private BanMessageConfig defaultBanConfig() {
        BanMessageConfig config = new BanMessageConfig();
        config.setEnabled(true);
        config.setBannedMessageBase(
                "&5&l緒山まひろ &b&l» &5&l呐呐~杂鱼哥哥不会这样就被&4&lBAN&5&l的不会说话了吧♡真是弱哎&5&l♡~ &f\n"
                        + "&b&l»&f&lPlayer: &4$playerName\n"
                        + "&b&l»&f&lReason: &c&l$reason&f&3&l\n"
                        + "&b&l»&f&lBanID : &c&l$banId\n"
                        + "&b&l»&f&lExpireTime : &c&l$expireTime\n"
                        + "&5&lFind out more:&b&l»&f&l http://www.nyacat.cloud &9");
        return config;
    }

    /** 读取并解析某个配置键；缺失/空白/解析失败返回 null。 */
    private <T> T parseConfig(String key, Class<T> clazz) {
        String val = cacheService.getConfig(key);
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            T config = JSON.parseObject(val.trim(), clazz);
            if (config == null) {
                log.error("Parsed {} to null (value: {})", key, val);
            }
            return config;
        } catch (Exception e) {
            log.error("Failed to parse {} (value: {}): {}", key, val, e.getMessage());
            return null;
        }
    }

    /** 把配置对象序列化后写回缓存（键不存在则新建）。 */
    private void writeConfig(String key, Object config) {
        String json = JSON.toJSONString(config);
        try {
            cacheService.updateConfig(key, json);
        } catch (Exception e) {
            try {
                cacheService.addConfig(key, json);
            } catch (Exception ignored) {
                // Already present or no transaction — the default is returned regardless.
            }
        }
    }

    /**
     * 一次性迁移：旧版本把封禁模板（字段 {@code banned_message_base}）放在 {@code proxy.kick-message} 下，
     * 现在拆分为独立的 {@code proxy.ban-message}。迁移后把 {@code proxy.kick-message} 重置为踢出默认模板。
     */
    private void ensureConfigsMigrated() {
        if (migrationChecked) {
            return;
        }
        synchronized (this) {
            if (migrationChecked) {
                return;
            }
            String kickVal = cacheService.getConfig(KEY_KICK_MESSAGE);
            if (kickVal != null && !kickVal.isBlank()) {
                try {
                    KickMessageConfig parsed = JSON.parseObject(kickVal.trim(), KickMessageConfig.class);
                    if (parsed != null && (parsed.getKickMessageBase() == null || parsed.getKickMessageBase().isBlank())) {
                        JSONObject raw = JSON.parseObject(kickVal.trim());
                        String oldBanTemplate = raw == null ? null : raw.getString("banned_message_base");
                        if (oldBanTemplate != null && !oldBanTemplate.isBlank()
                                && cacheService.getConfig(KEY_BAN_MESSAGE) == null) {
                            BanMessageConfig ban = new BanMessageConfig();
                            ban.setEnabled(raw.getBooleanValue("enabled", true));
                            ban.setBannedMessageBase(normalizeBanPlaceholders(oldBanTemplate));
                            writeConfig(KEY_BAN_MESSAGE, ban);
                            log.info("Migrated legacy ban template from {} to {}", KEY_KICK_MESSAGE, KEY_BAN_MESSAGE);
                        }
                        writeConfig(KEY_KICK_MESSAGE, defaultKickConfig());
                    }
                } catch (Exception e) {
                    log.warn("Legacy kick-message migration skipped: {}", e.getMessage());
                }
            }
            migrationChecked = true;
        }
    }

    /** 旧封禁模板占位符统一为新约定。 */
    private String normalizeBanPlaceholders(String template) {
        return template
                .replace("$idRandom", "$banId")
                .replace("$playerUID", "$playerName")
                .replace("$ExpireTime", "$expireTime")
                .replace("UUID:", "Player:");
    }

    public boolean isOnlineMode() {
        String val = cacheService.getConfig(KEY_ONLINE_MODE);
        if (val == null){
            cacheService.addConfig(KEY_ONLINE_MODE,"true");
            return true;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * 是否通过 BungeeCord 将玩家的 IP、UUID 和皮肤属性转发到后端
     * IP 转发。仅在后端的 spigot.yml 中启用 {@code bungeecord: true} 时需要。
     */
    public boolean isIpForward() {
        String val = cacheService.getConfig(KEY_IP_FORWARD);
        if (val == null){
            cacheService.addConfig(KEY_IP_FORWARD,"true");
            return true;
        }
        return Boolean.parseBoolean(val);
    }

    /**
     * 代理的显示名称，用于品牌插件消息（显示在客户端的 F3 屏幕中）
     * 以及服务器品牌重写中，类似于 BungeeCord 的 {@code bungee.name}。
     */
    public String getProxyName() {
        String val = cacheService.getConfig(KEY_NAME);
        if (val == null || val.isBlank()) {
            cacheService.addConfig(KEY_NAME, "NekoProxy");
            return "NekoProxy";
        }
        return val.trim();
    }

    /**
     * 是否启用 Forge (FML) 握手拦截，类似 BungeeCord 的{@code forge_support} 配置。
     * 启用后，代理将驱动客户端握手状态机，在服务器切换时重置它，并保护原版后端防止雷霆大数据包。
     */
    public boolean isForgeSupport() {
        String val = cacheService.getConfig(KEY_FORGE_SUPPORT);
        if (val == null) {
            cacheService.addConfig(KEY_FORGE_SUPPORT, "true");
            return true;
        }
        return Boolean.parseBoolean(val);
    }
}
