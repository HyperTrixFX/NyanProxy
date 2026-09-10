package moe.koseirin.nyanruaineo.Minecraft.listener;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.eventbus.Interface.EventHeader;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerDisconnectEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerJoinEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.PlayerLoginEvent;
import moe.koseirin.nyanruaineo.Minecraft.event.ProxyPingEvent;
import org.springframework.stereotype.Component;

/**
 * 代理事件监听器。项目中的 {@code EventBusAutoRegister} 会自动注册所有带有 {@link EventHeader} 注解的方法的 bean，
 * 因此额外的监听器可以按照同样的方式编写。
 *
 * <p>玩家命令不再在此处处理——它们作为 {@code ProxyCommand} 通过 {@code CommandManager} 注册
 * （参见 {@code BuiltinCommands}），并由 {@code UpstreamBridge} 分发，这与 BungeeCord 的
 * {@code PluginManager#registerCommand} 流程一致。</p>
 */
@Slf4j
@Component
public class ProxyEventListener {

    @EventHeader
    public void onPlayerLogin(PlayerLoginEvent event) {
//        log.info("[ProxyEvent] PlayerLogin: {} ({}) protocol={} from {} requesting {}",
//                event.username(), event.uuid(), event.protocolVersion(), event.ip(), event.requestedServer());
    }

    @EventHeader
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 降为 DEBUG：玩家进服的那条 INFO 由 InitialHandler#logConnectedToBackend 打印。
        // 同一个事件以前会打三遍（这里、InitialHandler、PlayerAuthService 的认证成功），
        // 高并发/集中重连时这些重复行既无信息增量，又会让同步的控制台 appender 阻塞事件循环。
        log.debug("[ProxyEvent] PlayerJoin: {} ({}) protocol={} joined {}:{}",
                event.username(), event.uuid(), event.protocolVersion(), event.serverHost(), event.serverPort());

    }

    @EventHeader
    public void onPlayerDisconnect(PlayerDisconnectEvent event) {
        // 保留 INFO：这是每个玩家会话唯一的「退出」记录。
        if (event.username() != null) log.info("[ProxyEvent] PlayerDisconnect: {} ({})", event.username(), event.uuid());

    }

    @EventHeader
    public void onProxyPing(ProxyPingEvent event) {
        log.debug("[ProxyEvent] ProxyPing: protocol={} from {}", event.protocolVersion(), event.ip());
    }
}
