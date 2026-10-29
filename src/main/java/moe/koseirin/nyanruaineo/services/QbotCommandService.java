package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerQueryService;
import moe.koseirin.nyanruaineo.Minecraft.service.ServerStatusService;
import moe.koseirin.nyanruaineo.NyanIdApplication;
import moe.koseirin.nyanruaineo.entity.AIChatMemory;
import moe.koseirin.nyanruaineo.repository.AIChatMemoryExtractionRepository;
import moe.koseirin.nyanruaineo.repository.QqBindingRepository;
import moe.koseirin.nyanruaineo.utils.AI.ChatContext;
import moe.koseirin.nyanruaineo.utils.AI.ChatPlatform;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotCommandCatalog;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotMessageContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qbot 固定指令路由。
 * <p>
 * 好友/群聊监听器只负责把 QQ 事件整理成 {@link QbotMessageContext}，所有命令解析、权限判断、
 * 冷却和回复都在这里完成，避免原实现中好友/群聊两份几乎相同的 switch。
 */
@Slf4j
@Service
public class QbotCommandService {

    private static final long AI_COOLDOWN_MS = 1200;
    private static final long FORGET_CONFIRM_MS = 60_000;
    private static final long UNBIND_CONFIRM_MS = 60_000;
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AIServices aiServices;
    private final AIChatMemoryService memoryService;
    private final AIChatMemoryExtractionRepository extractionRepository;

    private final QqBindingRepository qqBindingRepository;

    private final QqBindingService bindingService;
    private final QbotGameService gameService;
    private final ServerStatusService serverStatusService;
    private final PlayerQueryService playerQueryService;
    private final MinecraftProxy proxy;

    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingForget = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingUnbind = new ConcurrentHashMap<>();

    public QbotCommandService(AIServices aiServices,
                              AIChatMemoryService memoryService,
                              AIChatMemoryExtractionRepository extractionRepository, QqBindingRepository qqBindingRepository,
                              QqBindingService bindingService,
                              QbotGameService gameService,
                              ServerStatusService serverStatusService,
                              PlayerQueryService playerQueryService,
                              MinecraftProxy proxy) {
        this.aiServices = aiServices;
        this.memoryService = memoryService;
        this.extractionRepository = extractionRepository;
        this.qqBindingRepository = qqBindingRepository;
        this.bindingService = bindingService;
        this.gameService = gameService;
        this.serverStatusService = serverStatusService;
        this.playerQueryService = playerQueryService;
        this.proxy = proxy;
    }

    public void handle(QbotMessageContext context) {
        if (context == null || context.unionOpenid() == null || context.unionOpenid().isBlank()) {
            return;
        }
        String text = normalize(context.content());
        if (text.isBlank()) {
            return;
        }

        ParsedCommand parsed = parseCommand(text);
        if (parsed == null) {
            handleAi(context, text);
            return;
        }

        try {
            switch (parsed.name()) {
                case "help", "帮助", "菜单" -> context.send(QbotCommandCatalog.HELP);
                case "status", "状态", "服务器" -> handleStatus(context);
                case "list", "online", "在线", "列表" -> handleList(context);
                case "ping", "在吗" -> context.send("喵？咱在的！现在服务器时间是 "
                        + LocalDateTime.now().format(TIME_FORMATTER) + "，今天也要开开心心的喵～");
                case "nyanid" -> context.send("awa");
                case "d" -> context.send("1");
                case "game", "games", "游戏" -> context.send(gameService.menu());
                case "guess", "猜数字" -> context.send(gameService.startOrGuess(context.unionOpenid(), parsed.args()));
                case "rps", "猜拳" -> context.send(gameService.rps(context.unionOpenid(), parsed.args()));
                case "roll", "骰子" -> context.send(gameService.roll(context.unionOpenid(), parsed.args()));
                case "quiz", "问答" -> context.send(gameService.startQuiz(context.unionOpenid()));
                case "answer", "答案" -> context.send(gameService.answerQuiz(context.unionOpenid(), parsed.args()));
                case "story", "故事" -> context.send(gameService.story(context.unionOpenid(), parsed.args(),
                        context.displayName()));
                case "fortune", "运势", "抽签" -> context.send(gameService.fortune(context.unionOpenid()));
                case "giveup", "放弃" -> context.send(gameService.giveUp(context.unionOpenid()));
                case "memory", "记忆" -> handleMemory(context);
                case "forget", "忘记" -> handleForget(context, parsed.args());
                case "bind", "绑定" -> handleBind(context, parsed.args(),context.unionOpenid());
                case "unbind", "解绑" -> handleUnbind(context, parsed.args());
                case "bc", "广播" -> handleBroadcast(context, parsed.args());

                default -> handleAi(context, text);
            }
        } catch (Exception e) {
            log.error("Qbot command failed: {}", parsed.name(), e);
            context.send("呜…咱刚刚处理这条消息时卡住了喵，再试一次好不好？");
        }
    }

    /** 绑定后的显示名，未绑定时统一称呼“主人”。 */
    public String resolveDisplayName(String unionOpenid) {
        String name = bindingService.getDisplayName(unionOpenid);
        return name == null || name.isBlank() ? "主人" : name;
    }

    private void handleAi(QbotMessageContext context, String message) {
        if (!aiServices.isEnabled()) {
            context.send(QbotCommandCatalog.CHAT_UNAVAILABLE);
            return;
        }
        if (!allow("ai:" + context.unionOpenid(), AI_COOLDOWN_MS)) {
            context.send("慢一点喵…咱还在想上一条呢，等一小下下。");
            return;
        }
        String displayName = context.displayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = resolveDisplayName(context.unionOpenid());
        }
        ChatContext chatContext = new ChatContext(
                context.unionOpenid(),
                context.unionOpenid(),
                message,
                displayName,
                context.group() ? ChatPlatform.QQ_GROUP : ChatPlatform.QQ_PRIVATE
        );
        String reply = aiServices.chat(chatContext);
        if (reply == null || reply.isBlank()) {
            reply = "呜…咱刚刚走神了喵，再说一次好不好？";
        }
        context.send(reply);
    }

    private void handleStatus(QbotMessageContext context) {
        serverStatusService.getStatusesAsync()
                .thenAccept(statuses -> context.send(formatStatuses(statuses)))
                .exceptionally(error -> {
                    log.warn("Failed to query server status for Qbot", error);
                    context.send("呜…咱刚刚查服务器状态时卡住了喵，等会儿再试试。");
                    return null;
                });
    }

    private void handleList(QbotMessageContext context) {
        context.send(formatPlayers(playerQueryService.getOnlinePlayers()));
    }

    private void handleMemory(QbotMessageContext context) {
        String unionOpenid = context.unionOpenid();
        boolean bound = bindingService.getUid(unionOpenid) != null;
        long count = memoryService.count(unionOpenid);
        List<AIChatMemory> latest = memoryService.latest(unionOpenid, 5);
        StringBuilder sb = new StringBuilder();
        sb.append(bound ? "当前 QQ 已经绑定 NyanID 账号啦喵。\n" : "当前 QQ 还没有绑定 NyanID 喵。\n");
        sb.append("咱记住的事情：").append(count).append(" 件。\n");
        if (count == 0) {
            sb.append("咱还没有记住关于你的事情喵。多陪咱聊一会儿，咱会悄悄把重要的记下来。\n");
        } else if (!latest.isEmpty()) {
            sb.append("最近记住的：\n")
                    .append(aiServices.sanitizeUserFacing(memoryService.formatForUser(latest, 100)))
                    .append('\n');
        }
        sb.append("想把它们都忘掉，就发 /forget 喵。");
        context.send(sb.toString());
    }

    private void handleForget(QbotMessageContext context, String args) {
        String unionOpenid = context.unionOpenid();
        long now = System.currentTimeMillis();
        if (!"confirm".equalsIgnoreCase(args.trim())) {
            long count = memoryService.count(unionOpenid);
            pendingForget.put(unionOpenid, now + FORGET_CONFIRM_MS);
            context.send("咱会把当前这段聊天和咱记住的关于你的 " + count + " 件事情一起忘掉喵。\n"
                    + "如果确定，请在 60 秒内发送 /forget confirm。");
            return;
        }

        Long expireAt = pendingForget.remove(unionOpenid);
        if (expireAt == null || expireAt < now) {
            context.send("没有等待确认的忘记操作喵，先发 /forget 吧。");
            return;
        }
        long count = memoryService.count(unionOpenid);
        int deleted = memoryService.deleteAll(unionOpenid);
        aiServices.clearSession(unionOpenid);
        extractionRepository.deleteBySessionId(unionOpenid);
        context.send("好啦……咱已经把关于你的 " + Math.max(count, deleted) + " 件事情和当前聊天都忘掉了喵。\n"
                + "（尾巴有点蔫）");
    }

    private void handleBind(QbotMessageContext context, String args, String uuid) {
//        if (context.group()) {
//            context.send("绑定要在私聊里做喵。请添加咱为好友后，在私聊发送 /bind 你的邮箱。");
//            return;
//        }
        if (qqBindingRepository.findUid(uuid) != null) {
            context.send("你已经绑定了NyanID喵！");
            return;
        }


        if (args.isBlank()) {
            context.send("发送 /bind 你的邮箱 喵，咱会给邮箱发一个 6 位验证码。\n"
                    + "收到后发送 /bind 验证码 完成绑定。");
            return;
        }
        String value = args.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("verify ")) {
            value = value.substring(7).trim();
        }
        if (value.contains("@")) {
            QqBindingService.BindRequestResult result = bindingService.requestBindCode(context.unionOpenid(), value);
            context.send(result.message());
        } else {
            QqBindingService.BindVerifyResult result = bindingService.verifyBindCode(context.unionOpenid(), value);
            context.send(result.message());
        }
    }

    private void handleUnbind(QbotMessageContext context, String args) {
        if (context.group()) {
            context.send("解绑也要在私聊里做喵。");
            return;
        }
        String unionOpenid = context.unionOpenid();
        long now = System.currentTimeMillis();
        if (!"confirm".equalsIgnoreCase(args.trim())) {
            if (bindingService.getUid(unionOpenid) == null) {
                context.send("当前 QQ 还没有绑定 NyanID 账号喵。");
                return;
            }
            pendingUnbind.put(unionOpenid, now + UNBIND_CONFIRM_MS);
            context.send("解绑后，广播权限和“我的资料/封禁/工单”查询都会失效喵。\n"
                    + "咱记住的事情不会自动消失；想删掉就发 /forget。\n"
                    + "如果确定，请在 60 秒内发送 /unbind confirm。");
            return;
        }
        Long expireAt = pendingUnbind.remove(unionOpenid);
        if (expireAt == null || expireAt < now) {
            context.send("没有等待确认的解绑操作喵，先发 /unbind 吧。");
            return;
        }
        boolean deleted = bindingService.unbind(unionOpenid);
        context.send(deleted ? "已经解绑啦喵。以后想再绑定就发 /bind 邮箱。"
                : "当前 QQ 没有绑定记录喵。");
    }

    private void handleBroadcast(QbotMessageContext context, String args) {
        if (bindingService.getUid(context.unionOpenid()) == null) {
            context.send("全服广播需要先绑定 NyanID 并拥有管理员权限喵。请私聊发送 /bind 你的邮箱。");
            return;
        }
        if (!bindingService.hasBroadcastPermission(context.unionOpenid())) {
            context.send("呜……咱查了一下，你当前没有广播权限喵。");
            return;
        }
        if (args.isBlank()) {
            context.send("广播内容不能是空的喵，用法：/bc 要广播的内容");
            return;
        }
        if (args.length() > 200) {
            context.send("广播内容太长啦喵，请控制在 200 字以内。");
            return;
        }
        if (NyanIdApplication.wordBs.contains(args)) {
            context.send("这段广播内容里有不太合适的词喵，换一种说法吧。");
            return;
        }
        int delivered = proxy.broadcast("§b§l[全服广播] §r" + args);
        context.send(delivered > 0
                ? "广播出去啦喵，送到 " + delivered + " 位玩家。"
                : "现在没有玩家在线，广播没有送出去喵。");
    }

    private String formatStatuses(List<ServerStatusService.ServerStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return "咱还没有配置子服务器喵。";
        }
        long online = statuses.stream().filter(ServerStatusService.ServerStatus::online).count();
        StringBuilder sb = new StringBuilder("子服务器状态：在线 ")
                .append(online).append('/').append(statuses.size()).append('\n');
        for (ServerStatusService.ServerStatus status : statuses) {
            sb.append("- ").append(status.name()).append("：")
                    .append(status.online() ? "在线" : "离线")
                    .append(" · ").append(status.playerCount()).append(" 人\n");
        }
        return sb.toString().trim();
    }

    private String formatPlayers(List<PlayerQueryService.PlayerInfo> players) {
        if (players == null || players.isEmpty()) {
            return "现在没有玩家在线喵，服务器有点安静。";
        }
        StringBuilder sb = new StringBuilder("在线玩家 ").append(players.size()).append(" 人：\n");
        int limit = Math.min(players.size(), 50);
        for (int i = 0; i < limit; i++) {
            PlayerQueryService.PlayerInfo player = players.get(i);
            sb.append("- ").append(player.username())
                    .append(" [").append(player.serverName() == null ? "未知" : player.serverName()).append("]\n");
        }
        if (players.size() > limit) {
            sb.append("……还有 ").append(players.size() - limit).append(" 人喵。\n");
        }
        return sb.toString().trim();
    }

    private boolean allow(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        cooldowns.put(key, now);
        if (cooldowns.size() > 5000) {
            cooldowns.entrySet().removeIf(entry -> now - entry.getValue() > 600_000);
        }
        return true;
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String value = content.replaceAll("<@!?[^>]+>", " ")
                .replaceAll("@_user_\\d+", " ")
                .replace('\u0000', ' ')
                .replace('\u00A0', ' ')
                .trim();
        if (value.startsWith("／")) {
            value = "/" + value.substring(1);
        }
        return value;
    }

    private ParsedCommand parseCommand(String text) {
        if (text.isEmpty() || text.charAt(0) != '/') {
            return null;
        }
        String body = text.substring(1).trim();
        if (body.isEmpty()) {
            return new ParsedCommand("help", "");
        }
        int split = -1;
        for (int i = 0; i < body.length(); i++) {
            if (Character.isWhitespace(body.charAt(i))) {
                split = i;
                break;
            }
        }
        if (split < 0) {
            return new ParsedCommand(body.toLowerCase(Locale.ROOT), "");
        }
        return new ParsedCommand(body.substring(0, split).toLowerCase(Locale.ROOT),
                body.substring(split + 1).trim());
    }

    private record ParsedCommand(String name, String args) {
    }
}
