package moe.koseirin.nyanruaineo.Minecraft.service;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.config.ProxyProperties;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.FirewallConfig;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 连接防火墙：按来源 IP 限制新建连接速率、并发连接数、登录尝试/失败次数，超限后临时封禁该 IP。
 * 用于抵御机器人对代理和 Mojang 会话服务器的刷量攻击。
 */
@Slf4j
@Component
public class FirewallService {

    private final ProxyProperties properties;
    private final Map<String, IpState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;

    public FirewallService(ProxyProperties properties) {
        this.properties = properties;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "proxy-firewall-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        this.sweeper.scheduleAtFixedRate(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    private FirewallConfig config() {
        return properties.getFirewallConfig();
    }

    /**
     * 新连接到达时调用。返回 false 表示该连接应当被立即关闭（被封禁、连接速率超限或并发超限）。
     */
    public boolean tryConnect(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return true;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return true;
        }
        IpState state = states.computeIfAbsent(ip, key -> new IpState());
        synchronized (state) {
            if (isBanned(state, cfg)) {
//                log.warn("Firewall: rejected connection from banned IP {}", ip);
                return false;
            }

            long now = System.currentTimeMillis();
            if (prune(state.connectionTimes, now - 1_000) >= cfg.getMaxConnectionsPerSecond()) {
                ban(state, cfg, "exceeded " + cfg.getMaxConnectionsPerSecond() + " connections/sec", ip);
                return false;
            }
            state.connectionTimes.addLast(now);

            if (state.concurrent >= cfg.getMaxConcurrentPerIp()) {
                ban(state, cfg, "exceeded " + cfg.getMaxConcurrentPerIp() + " concurrent connections", ip);
                return false;
            }
            state.concurrent++;
            return true;
        }
    }

    /** 连接关闭时调用，用于释放该 IP 的并发额度。 */
    public void onDisconnect(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return;
        }
        IpState state = states.get(ip);
        if (state != null) {
            synchronized (state) {
                if (state.concurrent > 0) {
                    state.concurrent--;
                }
            }
        }
    }

    /**
     * 客户端发起登录（LoginStart）时调用。返回 false 表示登录应当被拒绝（被封禁或登录尝试超限）。
     * 在鉴权之前调用，避免机器人直接刷 Mojang 会话服务器。
     */
    public boolean tryLogin(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return true;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return true;
        }
        IpState state = states.computeIfAbsent(ip, key -> new IpState());
        synchronized (state) {
            if (isBanned(state, cfg)) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (prune(state.loginTimes, now - 60_000) >= cfg.getMaxLoginAttemptsPerMinute()) {
                ban(state, cfg, "exceeded " + cfg.getMaxLoginAttemptsPerMinute() + " login attempts/min", ip);
                return false;
            }
            state.loginTimes.addLast(now);
            return true;
        }
    }

    /**
     * 玩家成功进入后端（到达 play 阶段）后调用：清空该 IP 的全部速率计数
     * （连接速率、登录尝试、登录失败），后续重连从零开始，避免此前累积造成误封。
     * 并发数与封禁状态不受影响（并发由连接断开维护，封禁是独立状态）。
     */
    public void resetIp(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return;
        }
        IpState state = states.get(ip);
        if (state != null) {
            synchronized (state) {
                state.connectionTimes.clear();
                state.loginTimes.clear();
                state.loginFailureTimes.clear();
            }
        }
    }

    /** 登录鉴权成功后调用：一次成功会清空该 IP 的失败计数，避免偶发失败累积成误封。 */
    public void recordLoginSuccess(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return;
        }
        IpState state = states.get(ip);
        if (state != null) {
            synchronized (state) {
                state.loginFailureTimes.clear();
            }
        }
    }

    /** 登录鉴权失败后调用，失败次数超限时封禁该 IP。 */
    public void recordLoginFailure(InetSocketAddress address) {
        FirewallConfig cfg = config();
        if (!cfg.isEnabled()) {
            return;
        }
        String ip = ipOf(address);
        if (ip == null) {
            return;
        }
        IpState state = states.computeIfAbsent(ip, key -> new IpState());
        synchronized (state) {
            if (isBanned(state, cfg)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (prune(state.loginFailureTimes, now - 60_000) >= cfg.getMaxLoginFailuresPerMinute()) {
                ban(state, cfg, "exceeded " + cfg.getMaxLoginFailuresPerMinute() + " login failures/min", ip);
                return;
            }
            state.loginFailureTimes.addLast(now);
        }
    }

    /** 移除超出窗口的时间戳，返回窗口内剩余数量。 */
    private int prune(Deque<Long> times, long windowStart) {
        while (!times.isEmpty() && times.peekFirst() < windowStart) {
            times.pollFirst();
        }
        return times.size();
    }

    private boolean isBanned(IpState state, FirewallConfig cfg) {
        long until = state.bannedUntil;
        if (until == 0) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            state.bannedUntil = 0; // 封禁到期，自动解除
            return false;
        }
        return true;
    }

    private void ban(IpState state, FirewallConfig cfg, String reason, String ip) {
        state.bannedUntil = System.currentTimeMillis() + cfg.getBanDurationSeconds() * 1000L;
        //TODO IP属地查询
        log.warn("Firewall: banned IP {} for {} seconds ({})", ip, cfg.getBanDurationSeconds(), reason);
    }

    /** 定期清理已无活动的 IP 状态，避免长期占用内存。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, IpState> entry : states.entrySet()) {
            IpState state = entry.getValue();
            synchronized (state) {
                prune(state.connectionTimes, now - 60_000);
                prune(state.loginTimes, now - 60_000);
                prune(state.loginFailureTimes, now - 60_000);
                boolean stale = state.bannedUntil == 0
                        && state.concurrent == 0
                        && state.connectionTimes.isEmpty()
                        && state.loginTimes.isEmpty()
                        && state.loginFailureTimes.isEmpty();
                if (stale) {
                    states.remove(entry.getKey(), state);
                }
            }
        }
    }

    private static String ipOf(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    /** 单个 IP 的防火墙状态，所有变更都在其 monitor 内进行。 */
    private static final class IpState {
        volatile long bannedUntil;
        int concurrent;
        final Deque<Long> connectionTimes = new ArrayDeque<>();
        final Deque<Long> loginTimes = new ArrayDeque<>();
        final Deque<Long> loginFailureTimes = new ArrayDeque<>();
    }
}
