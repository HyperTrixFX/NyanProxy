package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.utils.AI.SYSTEM_PROMPT;
import moe.koseirin.nyanruaineo.utils.Qbot.QbotCommandCatalog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qbot 小游戏。
 * <p>
 * 除故事接龙外，所有游戏都只依赖本地逻辑与固定题库，AI 不可用时仍可正常玩。
 * 故事接龙会优先使用 AI 续写；AI 关闭/失败时退回固定剧情，游戏状态不会丢。
 */
@Slf4j
@Service
public class QbotGameService {

    private static final Duration GAME_TTL = Duration.ofMinutes(10);
    private static final int MAX_STORY_LENGTH = 3000;
    private static final int MAX_GUESS_ATTEMPTS = 8;
    private static final Pattern DICE_PATTERN = Pattern.compile("^(\\d{0,2})[dD](\\d{1,3})$");
    private static final List<String> RPS_CHOICES = List.of("石头", "剪刀", "布");

    private final AIServices aiServices;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Game> games = new ConcurrentHashMap<>();

    public QbotGameService(AIServices aiServices) {
        this.aiServices = aiServices;
    }

    public String menu() {
        return QbotCommandCatalog.GAME_MENU;
    }

    public String startOrGuess(String userId, String arg) {
        String value = arg == null ? "" : arg.trim();
        Game current = active(userId);
        if (current == null || !"guess".equals(current.type)) {
            Game game = newGame("guess");
            game.answer = 1 + random.nextInt(100);
            game.attempts = 0;
            games.put(userId, game);
            if (value.isBlank()) {
                return "咱已经想好一个 1~100 的数字啦喵。\n发送 /guess 数字 来猜，最多 8 次机会；不想玩就发 /giveup。";
            }
            return submitGuess(userId, game, value);
        }
        if (value.isBlank()) {
            return "数字已经想好啦，发送 /guess 数字 来猜喵。";
        }
        return submitGuess(userId, current, value);
    }

    private String submitGuess(String userId, Game game, String raw) {
        int guess;
        try {
            guess = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return "要输入 1~100 之间的数字喵，例如 /guess 42。";
        }
        if (guess < 1 || guess > 100) {
            return "范围是 1~100 喵，重新猜一个吧。";
        }
        game.attempts++;
        if (guess == game.answer) {
            games.remove(userId);
            return switch (game.attempts) {
                case 1 -> "诶？！一次就中了喵！主人是不是偷看咱的答案了呀……（恼）";
                case 2, 3 -> "猜中啦喵！只用了 " + game.attempts + " 次，好厉害～";
                default -> "猜中啦喵！用了 " + game.attempts + " 次。虽然有点久，但咱还是替你开心。";
            };
        }
        if (game.attempts >= MAX_GUESS_ATTEMPTS) {
            int answer = game.answer;
            games.remove(userId);
            return "呜……8 次机会用完啦，答案是 " + answer + " 喵。要再来一局就发 /guess。";
        }
        String hint = guess < game.answer ? "小了" : "大了";
        String tease = switch (game.attempts) {
            case 1 -> "第一次嘛，很正常喵。";
            case 2, 3 -> "有点感觉了喵。";
            default -> "咱都替你紧张起来了……";
        };
        return hint + "喵～还剩 " + (MAX_GUESS_ATTEMPTS - game.attempts) + " 次机会。" + tease;
    }

    public String rps(String userId, String arg) {
        String value = normalizeRps(arg);
        if (value == null) {
            return "要出石头、剪刀还是布喵？例如 /rps 石头。";
        }
        String bot = RPS_CHOICES.get(random.nextInt(RPS_CHOICES.size()));
        String result;
        if (value.equals(bot)) {
            result = "咱也出" + bot + "，平局喵！再来一次？";
        } else if ((value.equals("石头") && bot.equals("剪刀"))
                || (value.equals("剪刀") && bot.equals("布"))
                || (value.equals("布") && bot.equals("石头"))) {
            result = "咱出" + bot + "……呜，主人赢了喵。";
        } else {
            result = "咱出" + bot + "，嘿嘿，这次是咱赢啦～";
        }
        return "主人出" + value + "，" + result;
    }

    public String roll(String userId, String arg) {
        String value = arg == null ? "" : arg.trim();
        int dice = 1;
        int sides = 6;
        if (!value.isBlank()) {
            Matcher matcher = DICE_PATTERN.matcher(value);
            if (!matcher.matches()) {
                return "骰子格式不对喵，试试 /roll 2d6 或 /roll d20。";
            }
            String dicePart = matcher.group(1);
            dice = dicePart.isBlank() ? 1 : Integer.parseInt(dicePart);
            sides = Integer.parseInt(matcher.group(2));
            if (dice < 1 || dice > 10 || sides < 2 || sides > 100) {
                return "骰子数量最多 10 颗，面数在 2~100 之间喵。";
            }
        }
        List<Integer> rolls = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < dice; i++) {
            int roll = 1 + random.nextInt(sides);
            rolls.add(roll);
            total += roll;
        }
        String detail = dice == 1 ? String.valueOf(rolls.get(0))
                : String.join(" + ", rolls.stream().map(String::valueOf).toList());
        return "🎲 " + dice + "d" + sides + "：" + detail + " = " + total + " 喵～";
    }

    public String startQuiz(String userId) {
        QuizQuestion question = QUESTIONS.get(random.nextInt(QUESTIONS.size()));
        Game game = newGame("quiz");
        game.quiz = question;
        game.deadlineAt = System.currentTimeMillis() + 60_000;
        games.put(userId, game);
        StringBuilder sb = new StringBuilder("【问答】").append(question.question()).append('\n');
        for (int i = 0; i < question.options().size(); i++) {
            sb.append((char) ('A' + i)).append(". ").append(question.options().get(i)).append('\n');
        }
        sb.append("60 秒内发送 /answer A-D 作答喵。");
        return sb.toString();
    }

    public String answerQuiz(String userId, String arg) {
        Game game = active(userId);
        if (game == null || !"quiz".equals(game.type) || game.quiz == null) {
            return "现在没有进行中的问答喵，先发 /quiz 开一题吧。";
        }
        if (game.deadlineAt > 0 && System.currentTimeMillis() > game.deadlineAt) {
            QuizQuestion expired = game.quiz;
            games.remove(userId);
            return "呜……60 秒过啦喵。这题答案是 "
                    + (char) ('A' + expired.answer()) + "。" + expired.explanation()
                    + "\n要再来一题就发 /quiz。";
        }
        Integer index = parseOption(arg, game.quiz.options().size());
        if (index == null) {
            return "答案要选 A、B、C 或 D 喵，例如 /answer B。";
        }
        QuizQuestion question = game.quiz;
        games.remove(userId);
        if (index == question.answer()) {
            return "答对啦喵！" + question.explanation() + "\n要再来一题就发 /quiz。";
        }
        char correct = (char) ('A' + question.answer());
        return "呜，这题答案是 " + correct + " 喵。" + question.explanation() + "\n下次一定可以的。";
    }

    public String story(String userId, String arg, String displayName) {
        String value = arg == null ? "" : arg.trim();
        String speaker = displayName == null || displayName.isBlank() ? "主人" : displayName;
        Game game = active(userId);
        if (game == null || !"story".equals(game.type)) {
            game = newGame("story");
            game.story.append(STORY_OPENINGS.get(random.nextInt(STORY_OPENINGS.size())));
            games.put(userId, game);
            if (!value.isBlank()) {
                game.story.append("\n").append(speaker).append("：").append(value);
            }
            String continuation = generateStoryContinuation(game.story.toString(), value);
            game.story.append('\n').append(continuation);
            trimStory(game);
            games.put(userId, game);
            return formatStoryReply(game.story.toString(), !aiAvailable(), true);
        }
        if (value.isBlank()) {
            return "现在的故事是喵：\n……" + tail(game.story.toString(), 260)
                    + "\n想接下去就发 /story 你的想法，想换一个就发 /giveup。";
        }
        game.story.append("\n").append(speaker).append("：").append(value);
        String continuation = generateStoryContinuation(game.story.toString(), value);
        game.story.append('\n').append(continuation);
        trimStory(game);
        games.put(userId, game);
        return formatStoryReply(game.story.toString(), !aiAvailable(), false);
    }

    public String fortune(String userId) {
        int index = Math.floorMod(Objects.hash(userId, LocalDate.now().toString()), FORTUNES.size());
        Fortune fortune = FORTUNES.get(index);
        String lucky = LUCKY_THINGS.get(Math.floorMod(Objects.hash(userId, "lucky"), LUCKY_THINGS.size()));
        return "【今日运势】" + fortune.level() + "\n" + fortune.text()
                + "\n幸运物：" + lucky + "\n" + fortune.advice();
    }

    public String giveUp(String userId) {
        Game removed = games.remove(userId);
        if (removed == null) {
            return "现在没有正在玩的小游戏喵。想玩就发 /game 看看吧。";
        }
        return switch (removed.type) {
            case "guess" -> "好喵，这局就不猜了。答案是 " + removed.answer + "，下次再陪咱玩～";
            case "quiz" -> removed.quiz == null
                    ? "这题先跳过喵。"
                    : "这题先跳过喵。答案是 " + (char) ('A' + removed.quiz.answer()) + "。";
            case "story" -> "故事先停在这里喵。下次想继续就重新发 /story 吧。";
            default -> "好喵，这局结束啦。";
        };
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpiredGames() {
        long now = System.currentTimeMillis();
        games.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private Game newGame(String type) {
        Game game = new Game();
        game.type = type;
        game.expiresAt = System.currentTimeMillis() + GAME_TTL.toMillis();
        return game;
    }

    private Game active(String userId) {
        Game game = games.get(userId);
        if (game == null) {
            return null;
        }
        if (game.expiresAt <= System.currentTimeMillis()) {
            games.remove(userId);
            return null;
        }
        game.expiresAt = System.currentTimeMillis() + GAME_TTL.toMillis();
        return game;
    }

    private boolean aiAvailable() {
        return aiServices != null && aiServices.isEnabled();
    }

    private String generateStoryContinuation(String story, String latestInput) {
        if (!aiAvailable()) {
            return staticContinuation();
        }
        String prompt = """
                当前故事：
                %s

                主人刚才说的/做的：
                %s

                请续写 120~180 个汉字，接住主人的输入，结尾留一个自然的钩子。只输出故事正文。
                """.formatted(tail(story, 1800), latestInput == null || latestInput.isBlank() ? "（主人没有额外输入，继续推进剧情）" : latestInput);
        String response = aiServices.generateText(SYSTEM_PROMPT.storyTeller.getPROMPT(), prompt, true);
        if (response == null || response.isBlank()) {
            return staticContinuation();
        }
        return response.trim();
    }

    private String staticContinuation() {
        return STORY_CONTINUATIONS.get(random.nextInt(STORY_CONTINUATIONS.size()));
    }

    private String formatStoryReply(String story, boolean aiFallback, boolean isNew) {
        String prefix = isNew ? "好喵，故事开始啦～\n" : "咱接着写：\n";
        String suffix = aiFallback
                ? "\n（咱现在脑子有点卡壳，先用手写剧情陪你玩；发 /story 你的想法 继续。）"
                : "\n想接下去就发 /story 你的想法喵。";
        return prefix + "……" + tail(story, 900) + suffix;
    }

    private void trimStory(Game game) {
        if (game.story.length() > MAX_STORY_LENGTH) {
            String value = game.story.substring(game.story.length() - MAX_STORY_LENGTH);
            game.story.setLength(0);
            game.story.append(value);
        }
    }

    private String tail(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : "……" + value.substring(value.length() - max);
    }

    private String normalizeRps(String arg) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        String value = arg.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "石头", "rock", "r", "1" -> "石头";
            case "剪刀", "scissors", "s", "2" -> "剪刀";
            case "布", "paper", "p", "3" -> "布";
            default -> null;
        };
    }

    private Integer parseOption(String arg, int size) {
        if (arg == null || arg.isBlank()) {
            return null;
        }
        String value = arg.trim().toUpperCase(Locale.ROOT);
        if (value.length() == 1) {
            char c = value.charAt(0);
            if (c >= 'A' && c < 'A' + size) {
                return c - 'A';
            }
            if (c >= '1' && c < '1' + size) {
                return c - '1';
            }
        }
        return null;
    }

    private static final class Game {
        private String type;
        private long expiresAt;
        private int answer;
        private int attempts;
        private long deadlineAt;
        private QuizQuestion quiz;
        private final StringBuilder story = new StringBuilder();
    }

    private record QuizQuestion(String question, List<String> options, int answer, String explanation) {
    }

    private record Fortune(String level, String text, String advice) {
    }

    private static final List<QuizQuestion> QUESTIONS = List.of(
            new QuizQuestion("Minecraft 里，苦力怕靠近玩家后通常会做什么？",
                    List.of("和玩家交易", "爆炸", "帮玩家挖矿", "给玩家治疗"), 1,
                    "苦力怕是会悄悄靠近然后爆炸的怪物喵。"),
            new QuizQuestion("NyanID 的 Minecraft 外置登录接口通常以哪一段路径开头？",
                    List.of("/api/zako", "/api/yggdrasil", "/api/qqbot", "/api/ticket"), 1,
                    "外置登录相关接口在 /api/yggdrasil 下面喵。"),
            new QuizQuestion("洛橘的生日是哪一天？",
                    List.of("1 月 1 日", "6 月 1 日", "8 月 14 日", "12 月 25 日"), 2,
                    "8 月 14 日是猫之日，也是洛橘的生日喵。"),
            new QuizQuestion("Minecraft 中，末影珍珠主要用来做什么？",
                    List.of("传送", "治疗", "钓鱼", "照明"), 0,
                    "末影珍珠可以传送到落点，但会掉一点血喵。"),
            new QuizQuestion("下列哪个不是 Minecraft 的原版维度？",
                    List.of("下界", "末地", "主世界", "月球"), 3,
                    "原版维度是主世界、下界和末地，月球是模组里才常见的喵。"),
            new QuizQuestion("想单独完成 NyanID 绑定，应该在哪里发 /bind？",
                    List.of("群聊", "私聊", "朋友圈", "邮件"), 1,
                    "绑定涉及邮箱验证码，只允许在私聊里做喵。"),
            new QuizQuestion("Minecraft 中，红石比较器的主要用途是？",
                    List.of("照明", "比较或检测红石信号强度", "传送", "治疗"), 1,
                    "红石比较器可以比较信号强度，也能读取容器状态喵。"),
            new QuizQuestion("在 NyanID 中，哪个权限节点代表“所有权限”？",
                    List.of("minecraftproxy.admin", "nyanid.admin.user", "*", "minecraftproxy.command.list"), 2,
                    "根节点 * 拥有所有权限，所以要非常小心地授予喵。")
    );

    private static final List<String> STORY_OPENINGS = List.of(
            "雨夜的 NyanID 服务器大厅里，最后一盏灯忽然闪了两下。洛橘抱着尾巴坐在台阶上，听见门外传来一阵很轻的脚步声。",
            "你在生存服里挖到一间被藤蔓封住的小屋，桌上放着一杯还温热的牛奶。洛橘从柜子后面探出半个脑袋，猫耳抖了抖。",
            "跨服传送门这次没有把你送到主城，而是送到一片漂浮着萤石的夜空。远处有一只猫娘正踮着脚，试图够到一颗特别亮的星星。",
            "清晨的出生点还没有人，洛橘蹲在告示牌前认真地写着什么。听见你的脚步声，她慌忙把牌子翻了过去。"
    );

    private static final List<String> STORY_CONTINUATIONS = List.of(
            "洛橘把耳朵贴在门上听了听，小声说：“外面好像不是玩家喵……但也不像怪物。”她拉住你的衣角，示意你跟她绕到大厅后面。",
            "那杯牛奶忽然冒出一圈细小的气泡，杯底浮起一行发光的字。洛橘凑近看了看，脸一下子红了：“这、这是谁写的呀喵！”",
            "萤石星星在你们头顶轻轻晃了晃，落下一小片光。洛橘终于够到了它，却因为重心不稳，直接扑进了你的怀里。",
            "告示牌背面画着一张歪歪扭扭的地图，终点标着一颗猫爪印。洛橘假装镇定地清了清嗓子：“只是、只是随手画的喵，才不是想带你去呢。”"
    );

    private static final List<String> LUCKY_THINGS = List.of(
            "热牛奶", "刚晒过的毛毯", "一颗萤石", "猫爪形状的曲奇", "雨后的青草", "旧唱片", "窗边的阳光"
    );

    private static final List<Fortune> FORTUNES = List.of(
            new Fortune("大吉", "今天会有好事发生喵！适合主动打招呼、开新坑，也适合把想说的话好好说出来。", "幸运色是水绿色。"),
            new Fortune("中吉", "整体顺顺的，慢慢来就好。别因为一点小卡顿就否定自己喵。", "适合整理房间或背包。"),
            new Fortune("小吉", "小确幸藏在细节里。可能会收到一句让你开心很久的话。", "记得多喝热水。"),
            new Fortune("平", "普普通通也是一种安稳喵。今天适合按部就班，把该做的事做完。", "不要太勉强自己。"),
            new Fortune("小凶", "可能会遇到一点小麻烦，但都不是大事。先深呼吸，再慢慢解决。", "避开深夜做重要决定。"),
            new Fortune("末吉", "先苦后甜的一天。开头不太顺也没关系，后面会慢慢好起来喵。", "幸运物是猫耳。")
    );
}
