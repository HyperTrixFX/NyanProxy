package moe.koseirin.nyanruaineo.Minecraft.command;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.annotation.PostConstruct;

import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.ServerListConfig;
import moe.koseirin.nyanruaineo.Minecraft.connection.ServerConnection;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.service.*;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.RedisUtils.RedisService;
import moe.koseirin.nyanruaineo.utils.System.PermissionNodes;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用 {@link CommandManager} 注册代理自带的命令 ({@code /ping}、{@code /server}、{@code /broadcast}、
 * {@code /kick}、{@code /list}、{@code /status})，类似 BungeeCord 插件注册命令的方式。
 * 额外命令也是通过同样方式添加 — 构建一个 {@link ProxyCommand} 然后用 {@code commandManager.registerCommand(...)}—这样就可以在不改桥接或数据包流程的情况下扩展命令集合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinCommands {

    private final CommandManager commandManager;
    private final MinecraftProxy proxy;
    private final BackendServerManager backendServerManager;
    private final PlayerTransferService playerTransferService;
    private final PlayerKickService playerKickService;
    private final PlayerQueryService playerQueryService;
    private final ServerStatusService serverStatusService;
    private final ProxyBanService proxyBanService;
    private final YggdrasilRepository yggdrasilRepository;
    private final AccountsRepository accountsRepository;

    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^(\\d+)([smhdw])$");
    private final utilset utilset;
    private final RedisService redisService;

    @PostConstruct
    public void register() {
        commandManager.registerCommand(new ProxyCommand("bind") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleBind(sender);
            }
        });

        commandManager.registerCommand(new ProxyCommand("lobby",null,"hub") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                UserConnection user = userOf(sender);
                if (user == null) {
                    return;
                }
                ServerConnection current = user.getServer();
                ServerListConfig c = proxy.getProperties().getServerListConfig();
                BackendServer target = backendServerManager.findByName(c.getDefaultServer());
                if (current != null && !current.isClosed()
                        && target.getHost() != null && target.getHost().equalsIgnoreCase(current.getHost())
                        && target.getPort() == current.getPort()) {
                    sender.sendMessage("§eYou are already connected to " + target.getName() + "!");
                    return;
                }
                playerTransferService.transferIfOnline(user, target, sender::sendMessage);
            }
        });

        commandManager.registerCommand(new ProxyCommand("ip", PermissionNodes.COMMAND_IP) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleIp(sender, args);
            }

            @Override
            public List<String> onTabComplete(CommandSender sender, String[] args) {
                String prefix = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
                List<String> matches = new ArrayList<>();
                for (PlayerQueryService.PlayerInfo player : playerQueryService.getOnlinePlayers()) {
                    if (player.username() != null && player.username().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(player.username());
                    }
                }
                return matches;
            }
        });

        commandManager.registerCommand(new ProxyCommand("server", PermissionNodes.COMMAND_SERVER) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleServer(sender, String.join(" ", args));
            }

            @Override
            public List<String> onTabComplete(CommandSender sender, String[] args) {
                String prefix = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
                List<String> matches = new ArrayList<>();
                for (String name : backendServerManager.serverNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(name);
                    }
                }
                return matches;
            }
        });

        commandManager.registerCommand(new ProxyCommand("broadcast", PermissionNodes.COMMAND_BROADCAST, "bc") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                String message = String.join(" ", args);
                if (message.isEmpty()) {
                    sender.sendMessage("§cUsage: /broadcast <message>");
                    return;
                }
                int recipients = proxy.broadcast("§b§l[全服广播] §r" + message);
                sender.sendMessage("§aBroadcast delivered to " + recipients + " player(s).");
            }
        });

        commandManager.registerCommand(new ProxyCommand("kick", PermissionNodes.COMMAND_KICK) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleKick(sender, args);
            }
        });

        commandManager.registerCommand(new ProxyCommand("ban", PermissionNodes.COMMAND_BAN) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleBan(sender, args);
            }

            @Override
            public List<String> onTabComplete(CommandSender sender, String[] args) {
                String prefix = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";
                List<String> matches = new ArrayList<>();
                for (PlayerQueryService.PlayerInfo player : playerQueryService.getOnlinePlayers()) {
                    if (player.username() != null && player.username().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        matches.add(player.username());
                    }
                }
                return matches;
            }
        });

        commandManager.registerCommand(new ProxyCommand("list", PermissionNodes.COMMAND_LIST) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleList(sender);
            }
        });

        commandManager.registerCommand(new ProxyCommand("status", PermissionNodes.COMMAND_STATUS) {
            @Override
            public void execute(CommandSender sender, String[] args) {
                handleStatus(sender);
            }
        });
    }

    private UserConnection userOf(CommandSender sender) {
        return sender instanceof PlayerCommandSender player ? player.getUser() : null;
    }

    private void handleBind(CommandSender sender){
        UserConnection user = userOf(sender);
        if (user != null) {
            String uid = yggdrasilRepository.findNyanUidByUuid(user.getUuid().toString());
            if (uid != null) {
                sender.sendMessage("§c你不再需要进行NyanID绑定");
            }else {
                if (accountsRepository.GetUidByBind(user.getUuid().toString().replace("-","")) != null){
                    sender.sendMessage("§5§l小鳥遊ホシノ §b§l»§l§6该账号已被绑定!!");
                }else {
                if (redisService.getValue(user.getUuid().toString()+"BindAccount") == null){
                    UUID uuid = user.getUuid();
                    String BindCode = utilset.RandomNumber(6);
                    sender.sendMessage("§5§l小鳥遊ホシノ §b§l»§l§6The binding code has been obtained, and your binding code is as follows:§c§l»"+BindCode+"§l§6This binding code is valid for 180 seconds!!");
                    redisService.setValueWithExpiration(BindCode,uuid,180, TimeUnit.SECONDS);
                    redisService.setValueWithExpiration(uuid +"BindAccount",true,180, TimeUnit.SECONDS);
                }else {
                    sender.sendMessage("§5§l小鳥遊ホシノ §b§l»§l§6You have submitted an account binding request within 180 seconds. Please try again after 180 seconds!!");
                }
                }
            }
        }
    }



    /** {@code /ip [player]}：目标玩家的 IP，如果未提供名字，则为发送者自己的 IP。*/
    private void handleIp(CommandSender sender, String[] args) {
        String targetName = args.length > 0 ? args[0].trim() : "";
        if (targetName.isEmpty()) {
            UserConnection self = userOf(sender);
            if (self == null || self.getIp() == null) {
                sender.sendMessage("§cCannot resolve your IP.");
                return;
            }
            sender.sendMessage("§eYour IP: §a" + self.getIp());
            return;
        }

        UserConnection target = playerQueryService.getUserConnection(targetName);
        if (target == null || target.getIp() == null) {
            sender.sendMessage("§cPlayer not online: " + targetName);
            return;
        }
        sender.sendMessage("§e" + target.getUsername() + " §7IP: §a" + target.getIp());

    }

    /** {@code /server [name]}：列出服务器，或者将玩家切换到指定的服务器。 */
    private void handleServer(CommandSender sender, String args) {
        if (args.isEmpty()) {
            List<String> names = backendServerManager.serverNames();
            if (names.isEmpty()) {
                sender.sendMessage("§cNo sub-servers configured!");
            } else {
                sender.sendMessage("§eServers: §a" + String.join(", ", names));
            }
            return;
        }

        BackendServer target = backendServerManager.findByName(args);
        if (target == null) {
            sender.sendMessage("§cUnknown server: " + args);
            return;
        }

        UserConnection user = userOf(sender);
        if (user == null) {
            return;
        }
        ServerConnection current = user.getServer();
        if (current != null && !current.isClosed()
                && target.getHost() != null && target.getHost().equalsIgnoreCase(current.getHost())
                && target.getPort() == current.getPort()) {
            sender.sendMessage("§eYou are already connected to " + target.getName() + "!");
            return;
        }

        playerTransferService.transferIfOnline(user, target, sender::sendMessage);
    }

    /** 使用配置的踢出屏幕执行 {@code /kick <玩家> [原因]}。权限由 {@code minecraftproxy.command.kick} 控制。 */
    private void handleKick(CommandSender sender, String[] args) {
        UserConnection user = userOf(sender);
        if (user == null) {
            return;
        }

        String joined = String.join(" ", args);
        String[] parts = joined.split(" ", 2);
        String targetName = parts.length > 0 ? parts[0].trim() : "";
        if (targetName.isEmpty()) {
            sender.sendMessage("§cUsage: /kick <player> [reason]");
            return;
        }
        UserConnection target = playerQueryService.getUserConnection(targetName);
        if (target == null) {
            sender.sendMessage("§cPlayer not online: " + targetName);
            return;
        }
        String reason = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "Kicked by an operator";
        playerKickService.kick(target, reason,null);
        sender.sendMessage("§aKicked " + target.getUsername() + ".");
    }

    /** {@code /ban <玩家> [原因] [时长]}：封禁在线玩家（默认 type=5），时长如 30m/2h/7d，缺省为永久。 */
    private void handleBan(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: /ban <player> [reason] [duration(30m/2h/7d)]");
            return;
        }
        String targetName = args[0].trim();
        UserConnection target = playerQueryService.getUserConnection(targetName);
        if (target == null) {
            sender.sendMessage("§cPlayer not online: " + targetName);
            return;
        }

        String joined = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim() : "";
        String reason = "Banned by an operator";
        LocalDateTime expire = null;
        if (!joined.isEmpty()) {
            String[] tokens = joined.split(" ");
            LocalDateTime parsed = parseDuration(tokens[tokens.length - 1]);
            if (parsed != null) {
                expire = parsed;
                String rest = String.join(" ", Arrays.copyOfRange(tokens, 0, tokens.length - 1)).trim();
                if (!rest.isEmpty()) {
                    reason = rest;
                }
            } else {
                reason = joined;
            }
        }

        ProxyBanService.BanTarget banTarget = proxyBanService.resolveTarget(target.getUuid());
        if (banTarget == null) {
            sender.sendMessage("§c无法解析该玩家的封禁目标。");
            return;
        }
        BanUserList ban = proxyBanService.ban(banTarget, reason, expire, sender.getName(), ProxyBanService.TYPE_GAME_BAN);
        playerKickService.disconnect(target, proxyBanService.renderBanMessage(ban, target.getUsername()));
        sender.sendMessage("§a已封禁 " + target.getUsername() + " (BanID: " + ban.getBanID() + ")");
    }

    private LocalDateTime parseDuration(String token) {
        if (token == null) {
            return null;
        }
        Matcher m = DURATION_PATTERN.matcher(token.trim());
        if (!m.matches()) {
            return null;
        }
        long n = Long.parseLong(m.group(1));
        char unit = Character.toLowerCase(m.group(2).charAt(0));
        Duration d = switch (unit) {
            case 's' -> Duration.ofSeconds(n);
            case 'm' -> Duration.ofMinutes(n);
            case 'h' -> Duration.ofHours(n);
            case 'd' -> Duration.ofDays(n);
            case 'w' -> Duration.ofDays(n * 7);
            default -> null;
        };
        return d == null ? null : LocalDateTime.now().plus(d);
    }

    /** {@code /list}: 每个在线玩家及其当前服务器。 */
    private void handleList(CommandSender sender) {
        List<PlayerQueryService.PlayerInfo> players = playerQueryService.getOnlinePlayers();
        if (players.isEmpty()) {
            sender.sendMessage("§cNo players online.");
            return;
        }
        StringBuilder reply = new StringBuilder("§6§lOnline players (" + players.size() + "):");
        for (PlayerQueryService.PlayerInfo player : players) {
            reply.append("\n§7- §f").append(player.username())
                    .append(" §8[").append(player.serverName() == null ? "?" : player.serverName()).append(']');
        }
        sender.sendMessage(reply.toString());
    }

    /**{@code /status}：每个配置的服务器及其在线状态和玩家数量。 */
    private void handleStatus(CommandSender sender) {
        serverStatusService.getStatusesAsync().thenAccept(statuses -> {
            if (statuses.isEmpty()) {
                sender.sendMessage("§cNo sub-servers configured!");
                return;
            }
            StringBuilder reply = new StringBuilder("§6§lServer status:");
            for (ServerStatusService.ServerStatus status : statuses) {
                reply.append("\n§7- §f").append(status.name())
                        .append(" §8(").append(status.host()).append(':').append(status.port()).append(") ")
                        .append(status.online() ? "§aonline" : "§coffline")
                        .append("§7 players: §f").append(status.playerCount());
            }
            sender.sendMessage(reply.toString());
        });
    }
}
