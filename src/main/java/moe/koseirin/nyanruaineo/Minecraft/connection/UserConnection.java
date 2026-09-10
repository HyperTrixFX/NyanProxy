package moe.koseirin.nyanruaineo.Minecraft.connection;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeClientHandler;
import moe.koseirin.nyanruaineo.Minecraft.forge.ForgeServerHandler;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginSuccess;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PluginMessage;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 代理连接中负责与客户端交互的那一半.参考 BungeeCord 的 {@code UserConnection}。
 */
@Getter
public class UserConnection extends Connection {

    @Setter
    private String username;
    @Setter
    private UUID uuid;
    /**
     * -- GETTER --
     *  客户端在登录开始数据包中声明的 UUID（仅在 1.20.5+ 版本中存在）。
     */
    @Setter
    private UUID loginUuid;
    private List<LoginSuccess.Property> properties = new ArrayList<>();
    @Setter
    private int protocolVersion;
    /**
     * -- GETTER --
     *  客户端在其握手数据包中请求的服务器地址，用于后端路由。
     */
    @Setter
    private String requestedServer;
    /**
     * -- GETTER --
     *  握手主机名中剥离出的、用空字符分隔的附加数据（比如 FML 1.8+ 的 token）。
     *  如果关闭了 IP 转发，这些数据会在向后端握手时被掰回去。
     */
    @Setter
    private String extraDataInHandshake = "";
    @Setter
    private ServerConnection server;
    private int serverGeneration;
    /**
     * 标记一次“切换后端服务器”正在进行中。切换期间客户端在收到 StartConfiguration 前排队发出的
     * 残留 GAME 数据包必须被丢弃（参考 BungeeCord 的 {@code UpstreamBridge#shouldHandle}）。
     * 在 {@code PlayerTransferService#transfer} 置位，在新后端 JoinGame 到达时清除。
     */
    @Setter
    private volatile boolean switchingServer;
    /**
     * 已经由"后端掉线兜底"处理过的那条后端通道。后端关闭会经由 {@code ServerConnector} 的
     * closeFuture 与 {@code DownstreamBridge.channelInactive} 各通知一次，用通道本身当令牌，
     * 保证只有第一个通知真正触发回退大厅 / 踢出，第二个直接跳过（否则会把刚开始的回退打断）。
     * 新后端被采用后令牌自然失效（通道不同），所以后续掉线仍能再次兜底。
     */
    @Setter
    private volatile Channel handledDropChannel;
    /**
     * 记录 TabList ADD_PLAYER 数据包中的玩家名字，以便在拦截 TabList 前缀/后缀时，
     * 能够把 UPDATE_DISPLAY_NAME 条目还原成对应的玩家名。
     */
    private final Map<UUID, String> tabListNames = new HashMap<>();
    /**
     * 客户端通过 {@code REGISTER}/{@code minecraft:register} 注册的频道，这些频道会被跟踪，
     * 以便在切换服务器时能够将它们重播给新的后端（参考 BungeeCord 的
     * {@code InitialHandler.registeredChannels}）。
     */
    private final Set<String> registeredChannels = new HashSet<>();
    /**
     * 客户端的品牌插件消息（即 minecraft:brand 内容），会在玩家登录时重新发送给后端服务器，
     * 与 BungeeCord 中 {@code InitialHandler.brandMessage} 的行为一致。
     */
    @Setter
    private PluginMessage brandMessage;
    /**
     * 客户端在配置阶段发出的 client information（1.20.2+，含 skinParts 皮肤层/披风标志）。
     * 切换服务器时需要重放给新后端，否则后端会把皮肤层当作全关（参考 BungeeCord 的
     * {@code UserConnection#setSettings / getSettings}）。
     */
    @Setter
    private moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientSettings clientSettings;
    /**
     * 负责处理 Forge 客户端握手的处理器，每个连接对应一个实例，其角色与 BungeeCord 中的 {@code UserConnection} 类似。
     */
    private final ForgeClientHandler forgeClientHandler;
    /**
     * 当前正在连接的后端所对应的 Forge 握手处理器。当后端被确认为 Forge 服务端时，这个字段就会被赋值，
     * 功能类似 BungeeCord 中的 {@code UserConnection.forgeServerHandler}。
     */
    @Setter
    private ForgeServerHandler forgeServerHandler;

    public UserConnection(Channel channel) {
        super(channel);
        this.forgeClientHandler = new ForgeClientHandler(this);
    }

    /**
     * 提升服务器连接的版本代次。一旦开始切换后端服务器，那些监听旧连接关闭事件的处理器就不再允许把客户端踹下线了。
     */
    public void nextServerGeneration() {
        this.serverGeneration++;
    }

    public void setProperties(List<LoginSuccess.Property> properties) {
        this.properties = properties == null ? new ArrayList<>() : properties;
    }

    /**
     * 客户端的远程IP地址，若通道没有远程地址则为 {@code null}。
     */
    public String getIp() {
        Channel ch = getChannel();
        if (ch == null || !(ch.remoteAddress() instanceof InetSocketAddress remote)) {
            return null;
        }
        return remote.getAddress().getHostAddress();
    }

    /**
     * 客户端是否为 Forge 客户端（通过握手时携带的 FML 1.8 令牌，或在握手期间收到的 FML 模组列表来判断）。
     */
    public boolean isForgeUser() {
        return forgeClientHandler.isForgeUser();
    }

    /**
     * 记录客户端发出的 REGISTER、UNREGISTER 以及品牌（brand）插件消息，处理方式参考 BungeeCord 的
     * {@code InitialHandler.relayMessage}。但这些消息的最终转发给后端的工作还是由调用方负责。
     */
    public void trackPluginMessage(PluginMessage input) {
        String tag = input.getTag();
        if ("REGISTER".equals(tag) || "minecraft:register".equals(tag)) {
            String content = new String(input.getData(), StandardCharsets.UTF_8);
            for (String id : content.split("\0")) {
                if (registeredChannels.size() >= 128) {
                    throw new IllegalStateException("Too many registered channels");
                }
                if (id.length() >= 128) {
                    throw new IllegalArgumentException("Channel name too long");
                }
                registeredChannels.add(id);
            }
        } else if ("UNREGISTER".equals(tag) || "minecraft:unregister".equals(tag)) {
            String content = new String(input.getData(), StandardCharsets.UTF_8);
            for (String id : content.split("\0")) {
                registeredChannels.remove(id);
            }
        } else if ("MC|Brand".equals(tag) || "minecraft:brand".equals(tag)) {
            brandMessage = input;
        }
    }

    /**
     * 向该玩家发送插件消息，与 BungeeCord 的 {@code ProxiedPlayer.sendData} 方法行为一致。
     */
    public void sendData(String channel, byte[] data) {
        sendPacket(new PluginMessage(channel, data));
    }

}
