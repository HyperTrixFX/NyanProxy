package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.service.BackendServerManager;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerQueryService;
import moe.koseirin.nyanruaineo.Minecraft.service.ServerStatusService;
import moe.koseirin.nyanruaineo.entity.Accounts;
import moe.koseirin.nyanruaineo.entity.AIChatMemory;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.entity.NyanIDuser;
import moe.koseirin.nyanruaineo.entity.UserTicket;
import moe.koseirin.nyanruaineo.entity.Yggdrasil;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.NyanIDuserRepository;
import moe.koseirin.nyanruaineo.repository.UserTicketRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotCommandCatalog;
import moe.koseirin.nyanruaineo.utils.SqlService.impl.NyanidUserServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 的只读工具集合。
 * <p>
 * 安全约束：
 * <ul>
 *     <li>所有工具只查询，不提供广播、封禁、踢人、改资料等写操作；</li>
 *     <li>封禁/工单/个人资料只使用当前 union_openid 的绑定 uid，不接受任意 uid 参数；</li>
 *     <li>记忆只查询当前 union_openid 自己的记录。</li>
 * </ul>
 */
@Slf4j
@Service
public class AIToolService {

    private static final String BIND_HINT =
            "当前 QQ 还没有绑定 NyanID 账号。请先在私聊发送 /bind 你的邮箱，按提示完成绑定喵。";
    private static final String NO_OWNER = "咱现在想不起以前的事情喵。";
    private static final int MAX_RESULT_LENGTH = 6000;

    private static final String TOOLS_JSON = """
            [
              {
                "type": "function",
                "function": {
                  "name": "recall_memory",
                  "description": "检索当前用户以前聊天中记住的事情（只读）。当用户提到以前的事、偏好、身份、约定，或当前聊天没有上下文时使用。只能检索当前用户自己的记录。",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "query": {"type": "string", "description": "检索关键词，例如：称呼、游戏偏好、服务器、生日；留空则返回最近记忆"},
                      "limit": {"type": "integer", "description": "返回条数，1-8，默认 5", "minimum": 1, "maximum": 8}
                    },
                    "required": []
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_server_status",
                  "description": "查询所有子服务器的在线状态与在线玩家数（只读）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "list_online_players",
                  "description": "列出当前在线玩家及其所在子服务器（只读，最多 50 人）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "find_online_player",
                  "description": "查询指定玩家当前是否在线、在哪个子服务器（只读）",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "name": {"type": "string", "description": "玩家用户名"}
                    },
                    "required": ["name"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "list_backend_servers",
                  "description": "列出已配置的子服务器名称和在线状态（只读）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_user_by_uid",
                  "description": "根据 NyanID UID 查询用户的公开资料（昵称、简介、经验值，只读）",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "uid": {"type": "string", "description": "NyanID 用户的 32 位 UID"}
                    },
                    "required": ["uid"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "find_users_by_nickname",
                  "description": "根据昵称模糊查询 NyanID 用户公开资料，最多 10 条（只读）",
                  "parameters": {
                    "type": "object",
                    "properties": {
                      "nickname": {"type": "string", "description": "昵称的一部分或全部"}
                    },
                    "required": ["nickname"]
                  }
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_my_profile",
                  "description": "查询当前 QQ 已绑定 NyanID 账号自己的资料（只读；未绑定会返回绑定引导）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_my_ban_status",
                  "description": "查询当前绑定账号自己及其 Minecraft 角色的封禁记录（只读；未绑定会返回绑定引导）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_my_tickets",
                  "description": "查询当前绑定账号自己提交的工单状态（只读；未绑定会返回绑定引导）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_current_time",
                  "description": "获取服务器当前日期、时间与星期（只读）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              },
              {
                "type": "function",
                "function": {
                  "name": "get_bot_help",
                  "description": "获取洛橘的固定指令列表和小游戏菜单（只读）",
                  "parameters": {"type": "object", "properties": {}, "required": []}
                }
              }
            ]
            """;

    private static final List<Object> TOOLS = JSON.parseArray(TOOLS_JSON);
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss EEEE", Locale.CHINA);

    private final NyanidUserServiceImpl nyanidUserService;
    private final AIChatMemoryService memoryService;
    private final QqBindingService bindingService;
    private final ServerStatusService serverStatusService;
    private final PlayerQueryService playerQueryService;
    private final BackendServerManager backendServerManager;
    private final AccountsRepository accountsRepository;
    private final NyanIDuserRepository nyanIDuserRepository;
    private final BanUserRepository banUserRepository;
    private final UserTicketRepository userTicketRepository;
    private final YggdrasilRepository yggdrasilRepository;

    @Value("${ai.memory.enable:true}")
    private boolean memoryEnabled;

    public AIToolService(NyanidUserServiceImpl nyanidUserService,
                         AIChatMemoryService memoryService,
                         QqBindingService bindingService,
                         ServerStatusService serverStatusService,
                         PlayerQueryService playerQueryService,
                         BackendServerManager backendServerManager,
                         AccountsRepository accountsRepository,
                         NyanIDuserRepository nyanIDuserRepository,
                         BanUserRepository banUserRepository,
                         UserTicketRepository userTicketRepository,
                         YggdrasilRepository yggdrasilRepository) {
        this.nyanidUserService = nyanidUserService;
        this.memoryService = memoryService;
        this.bindingService = bindingService;
        this.serverStatusService = serverStatusService;
        this.playerQueryService = playerQueryService;
        this.backendServerManager = backendServerManager;
        this.accountsRepository = accountsRepository;
        this.nyanIDuserRepository = nyanIDuserRepository;
        this.banUserRepository = banUserRepository;
        this.userTicketRepository = userTicketRepository;
        this.yggdrasilRepository = yggdrasilRepository;
    }

    public List<Object> getTools() {
        return TOOLS;
    }

    /**
     * 执行一个只读工具。任何异常都转成自然语言结果，避免把堆栈或内部类名暴露给模型/用户。
     */
    public String execute(String toolName, Map<String, Object> args, String ownerOpenid) {
        if (toolName == null || toolName.isBlank()) {
            return "未知工具。";
        }
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        try {
            return truncate(switch (toolName) {
                case "recall_memory" -> recallMemory(ownerOpenid, safeArgs);
                case "get_server_status" -> serverStatus();
                case "list_online_players" -> onlinePlayers();
                case "find_online_player" -> findOnlinePlayer(stringArg(safeArgs, "name"));
                case "list_backend_servers" -> backendServers();
                case "get_user_by_uid" -> getUserByUid(stringArg(safeArgs, "uid"));
                case "find_users_by_nickname" -> findUsersByNickname(stringArg(safeArgs, "nickname"));
                case "get_my_profile" -> myProfile(ownerOpenid);
                case "get_my_ban_status" -> myBanStatus(ownerOpenid);
                case "get_my_tickets" -> myTickets(ownerOpenid);
                case "get_current_time" -> currentTime();
                case "get_bot_help" -> QbotCommandCatalog.HELP + "\n" + QbotCommandCatalog.GAME_MENU;
                default -> "未知工具。";
            }, MAX_RESULT_LENGTH);
        } catch (Exception e) {
            log.warn("AI tool {} failed for owner {}", toolName, ownerOpenid, e);
            return "工具暂时不可用喵，请稍后再试。";
        }
    }

    private String recallMemory(String ownerOpenid, Map<String, Object> args) {
        if (!memoryEnabled) {
            return "咱现在想不起以前的事情喵。";
        }
        if (ownerOpenid == null || ownerOpenid.isBlank()) {
            return NO_OWNER;
        }
        String query = stringArg(args, "query");
        int limit = intArg(args, "limit", 5);
        List<AIChatMemory> memories = memoryService.recall(ownerOpenid, query, limit);
        return memoryService.formatForTool(memories);
    }

    private String serverStatus() {
        List<ServerStatusService.ServerStatus> statuses = serverStatusService.getStatuses();
        if (statuses.isEmpty()) {
            return "当前没有配置子服务器。";
        }
        long online = statuses.stream().filter(ServerStatusService.ServerStatus::online).count();
        StringBuilder sb = new StringBuilder();
        sb.append("子服务器状态：在线 ").append(online).append('/').append(statuses.size()).append('\n');
        for (ServerStatusService.ServerStatus status : statuses) {
            sb.append("- ").append(status.name()).append("：")
                    .append(status.online() ? "在线" : "离线")
                    .append("，玩家 ").append(status.playerCount()).append(" 人\n");
        }
        return sb.toString().trim();
    }

    private String onlinePlayers() {
        List<PlayerQueryService.PlayerInfo> players = playerQueryService.getOnlinePlayers();
        if (players.isEmpty()) {
            return "当前没有玩家在线。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("当前在线玩家 ").append(players.size()).append(" 人：\n");
        int limit = Math.min(players.size(), 50);
        for (int i = 0; i < limit; i++) {
            PlayerQueryService.PlayerInfo player = players.get(i);
            sb.append("- ").append(player.username())
                    .append(" [").append(player.serverName() == null ? "未知" : player.serverName()).append("]\n");
        }
        if (players.size() > limit) {
            sb.append("……还有 ").append(players.size() - limit).append(" 人。\n");
        }
        return sb.toString().trim();
    }

    private String findOnlinePlayer(String name) {
        if (name.isBlank()) {
            return "请提供玩家用户名。";
        }
        PlayerQueryService.PlayerInfo player = playerQueryService.findPlayer(name);
        if (player == null) {
            return "玩家 " + name + " 当前不在线。";
        }
        return "玩家 " + player.username() + " 在线，位于 "
                + (player.serverName() == null ? "未知子服务器" : player.serverName()) + "。";
    }

    private String backendServers() {
        List<BackendServer> servers = backendServerManager.listServers();
        if (servers.isEmpty()) {
            return "当前没有配置子服务器。";
        }
        Map<String, ServerStatusService.ServerStatus> statusByName = new LinkedHashMap<>();
        for (ServerStatusService.ServerStatus status : serverStatusService.getStatuses()) {
            statusByName.put(status.name(), status);
        }
        StringBuilder sb = new StringBuilder("已配置的子服务器：\n");
        for (BackendServer server : servers) {
            ServerStatusService.ServerStatus status = statusByName.get(server.getName());
            sb.append("- ").append(server.getName()).append("：");
            if (status == null) {
                sb.append("状态未知\n");
            } else {
                sb.append(status.online() ? "在线" : "离线")
                        .append("，玩家 ").append(status.playerCount()).append(" 人\n");
            }
        }
        return sb.toString().trim();
    }

    private String getUserByUid(String uid) {
        if (uid.isBlank()) {
            return "请提供要查询的 UID。";
        }
        if (uid.length() > 64) {
            return "UID 格式看起来不太对喵。";
        }
        return nyanidUserService.getUserByUid(uid);
    }

    private String findUsersByNickname(String nickname) {
        if (nickname.isBlank()) {
            return "请提供要查询的昵称。";
        }
        if (nickname.length() > 64) {
            return "昵称太长了喵，换短一点的关键词试试。";
        }
        return nyanidUserService.findUsersByNickname(nickname);
    }

    private String myProfile(String ownerOpenid) {
        String uid = bindingService.getUid(ownerOpenid);
        if (uid == null) {
            return BIND_HINT;
        }
        Accounts account = accountsRepository.GetUser(uid);
        NyanIDuser user = nyanIDuserRepository.getUser(uid);
        if (account == null && user == null) {
            return "没有找到当前绑定账号的资料，可能数据正在同步喵。";
        }
        StringBuilder sb = new StringBuilder("当前绑定账号的资料：\n");
        if (account != null) {
            sb.append("用户名：").append(nullSafe(account.getUsername())).append('\n');
        }
        if (user != null) {
            sb.append("昵称：").append(nullSafe(user.getNickname())).append('\n');
            sb.append("经验值：").append(user.getExp()).append('\n');
            sb.append("简介：").append(truncate(nullSafe(user.getDescription()), 200)).append('\n');
            sb.append("开发者：").append(user.isIsDeveloper() ? "是" : "否").append('\n');
        }
        Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(uid);
        if (yggdrasil != null) {
            sb.append("Minecraft 角色：").append(nullSafe(yggdrasil.getPlayername()))
                    .append("（").append(yggdrasil.getUuid()).append("）\n");
        }
        return sb.toString().trim();
    }

    private String myBanStatus(String ownerOpenid) {
        String uid = bindingService.getUid(ownerOpenid);
        if (uid == null) {
            return BIND_HINT;
        }
        List<BanUserList> bans = new ArrayList<>();
        bans.addAll(banUserRepository.searchByUid(uid, PageRequest.of(0, 20)).getContent());

        Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(uid);
        if (yggdrasil != null && yggdrasil.getUuid() != null) {
            String uuid = yggdrasil.getUuid().replace("-", "");
            bans.addAll(banUserRepository.searchByUid(uuid, PageRequest.of(0, 10)).getContent());
        }

        Map<String, BanUserList> unique = new LinkedHashMap<>();
        for (BanUserList ban : bans) {
            unique.putIfAbsent(ban.getBanID(), ban);
        }
        if (unique.isEmpty()) {
            return "当前绑定账号没有任何封禁记录。";
        }
        LocalDateTime now = LocalDateTime.now();
        StringBuilder active = new StringBuilder();
        StringBuilder history = new StringBuilder();
        for (BanUserList ban : unique.values()) {
            boolean expired = ban.getExpireTime() != null && !ban.getExpireTime().isAfter(now);
            boolean effective = ban.isActive() && !expired;
            String line = "- " + banTypeLabel(ban.getType())
                    + "，原因：" + truncate(nullSafe(ban.getReason()), 80)
                    + "，开始：" + formatDate(ban.getBanTime())
                    + "，到期：" + (ban.getExpireTime() == null ? "永久" : formatDate(ban.getExpireTime()))
                    + "，状态：" + (effective ? "生效中" : (expired ? "已过期" : "已解除")) + "\n";
            if (effective) {
                active.append(line);
            } else {
                history.append(line);
            }
        }
        StringBuilder sb = new StringBuilder("当前绑定账号的封禁记录：\n");
        if (active.isEmpty()) {
            sb.append("生效中的封禁：无\n");
        } else {
            sb.append("生效中的封禁：\n").append(active);
        }
        if (!history.isEmpty()) {
            sb.append("历史记录：\n").append(truncate(history.toString(), 1500));
        }
        return sb.toString().trim();
    }

    private String myTickets(String ownerOpenid) {
        String uid = bindingService.getUid(ownerOpenid);
        if (uid == null) {
            return BIND_HINT;
        }
        List<UserTicket> tickets = userTicketRepository.findByUseridOrderByCreatedAtDesc(uid);
        if (tickets.isEmpty()) {
            return "当前绑定账号还没有提交过工单。";
        }
        StringBuilder sb = new StringBuilder("当前绑定账号的工单（最近 5 条）：\n");
        int limit = Math.min(tickets.size(), 5);
        for (int i = 0; i < limit; i++) {
            UserTicket ticket = tickets.get(i);
            sb.append("- ").append(ticketTypeLabel(ticket.getType()))
                    .append("，状态：").append(ticketStatusLabel(ticket.getStatus()))
                    .append("，创建：").append(formatDate(ticket.getCreatedAt()));
            if (ticket.getReply() != null && !ticket.getReply().isBlank()) {
                sb.append("，回复：").append(truncate(ticket.getReply(), 80));
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String currentTime() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        return now.format(DATE_TIME_FORMATTER) + "（" + now.getZone().getId() + "）";
    }

    private String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private int intArg(Map<String, Object> args, String key, int defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String banTypeLabel(int type) {
        return switch (type) {
            case BanUserList.TYPE_GAME_BAN -> "游戏封禁";
            case BanUserList.TYPE_DEAD_BAN -> "永久封禁";
            default -> "封禁类型 " + type;
        };
    }

    private String ticketTypeLabel(int type) {
        return switch (type) {
            case UserTicket.TYPE_BAN_APPEAL -> "封禁申诉";
            case UserTicket.TYPE_DEV_APPLY -> "开发者申请";
            case UserTicket.TYPE_ACCOUNT_SECURITY -> "账号安全申诉";
            default -> "工单类型 " + type;
        };
    }

    private String ticketStatusLabel(moe.koseirin.nyanruaineo.utils.System.EnumList.TicketStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case PENDING -> "待处理";
            case PROCESSING -> "处理中";
            case APPROVED -> "已通过";
            case REJECTED -> "已拒绝";
            case CLOSED -> "已关闭";
        };
    }

    private String formatDate(LocalDateTime value) {
        return value == null ? "未知" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max - 1);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }
}
