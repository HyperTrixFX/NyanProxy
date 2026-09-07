package moe.koseirin.nyanruaineo.Minecraft.command;

/*
 * @author KoseiRin_
 * awa
 */

import java.util.Collections;
import java.util.List;

/**
 * 一个代理命令，滑 BungeeCord 的 {@code Command}。命令通过 {@link CommandManager} 注册；
 * 管理器会将玩家的命令行分发给匹配的命令，并将每个命令名称注入客户端的命令树中（用于自动补全）。
 */
public abstract class ProxyCommand {

    private final String name;
    private final String permission;
    private final String[] aliases;

    public ProxyCommand(String name) {
        this(name, null);
    }

    public ProxyCommand(String name, String permission, String... aliases) {
        this.name = name;
        this.permission = permission;
        this.aliases = aliases == null ? new String[0] : aliases;
    }

    /** 执行命令。{@code args} 是命令名后的空格分割部分。 */
    public abstract void execute(CommandSender sender, String[] args);

    /**
     * 命令参数的标签补全（BungeeCord {@code TabExecutor}）。
     * {@code args} 是命令名之后用空格分开的部分；最后一个元素是正在补全的单词。
     * 返回的字符串会在需要时被调用者过滤。
     */
    public List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }

    /** 这个发送者是否可以运行命令（在权限系统之前不进行权限检查）。 */
    public boolean hasPermission(CommandSender sender) {
        return permission == null || permission.isEmpty() || sender.hasPermission(permission);
    }

    public String getName() {
        return name;
    }

    public String getPermission() {
        return permission;
    }

    public String[] getAliases() {
        return aliases;
    }
}
