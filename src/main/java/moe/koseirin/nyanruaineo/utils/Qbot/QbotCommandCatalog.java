package moe.koseirin.nyanruaineo.utils.Qbot;

/**
 * 机器人固定指令的展示文本。集中放在这里，避免 Qbot 指令层和 AI 工具各写一份。
 */
public final class QbotCommandCatalog {

    private QbotCommandCatalog() {
    }

    public static final String HELP = """
            【洛橘会做的事】
            /status   看看各子服务器状态
            /list     看看谁在线
            /ping     跟咱打个招呼
            /game     小游戏菜单
            /memory   看看咱记住了你的什么
            /forget   忘掉咱记住的关于你的事情（需要二次确认）
            /bind 邮箱      绑定 NyanID（仅私聊，会发邮箱验证码）
            /unbind          解绑当前 QQ（仅私聊，需要二次确认）
            /bc 消息         全服广播（需已绑定且拥有管理员权限）

            直接发消息就是和咱聊天喵～没对上指令的内容，咱也会接着聊。
            想看游戏怎么玩，发 /game。
            """;
    public static final String GAME_MENU = """
            【小游戏菜单】
            /guess          开始猜数字（1~100，8 次机会）
            /guess 数字     提交你的猜测
            /rps 石头|剪刀|布   猜拳
            /roll 2d6       掷骰子（默认 1d6，最多 10 颗、100 面）
            /quiz           问答小游戏（/answer A-D 作答）
            /story          故事接龙（咱脑袋卡壳时也能用固定剧情继续）
            /story 你的想法 把想法接进故事
            /fortune        今日运势
            /giveup         放弃当前游戏

            这些游戏咱自己就能陪主人玩喵。
            """;

    public static final String CHAT_UNAVAILABLE =
            "呜……咱现在脑袋有点晕乎乎的，先陪主人玩点别的吧。可以发 /game 玩小游戏，或者用 /status、/list 看看服务器。";
}
