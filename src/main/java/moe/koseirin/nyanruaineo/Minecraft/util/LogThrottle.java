package moe.koseirin.nyanruaineo.Minecraft.util;

/*
 * @author KoseiRin_
 * awa
 */

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按 key 给日志限流，用来压制「同一类错误在短时间内刷屏」的情况。
 * <p>
 * 代理端的某些告警是「每个失败连接一条」的：后端子服挂掉、玩家集体重连、或某个后端一直拒绝
 * 登录时，这些带堆栈的 warn/error 会以每秒成百上千条的速度刷出来。同步的控制台 appender
 * 会造成事件循环线程在写日志上阻塞，日志本身反而成了性能问题。
 * <p>
 * 用法：
 * <pre>{@code
 * String gate = LogThrottle.acquire("backend-login-error", 10_000L);
 * if (gate != null) {
 *     log.warn("Backend login error for {},[{}]{}", user.getUsername(), cause.getMessage(), gate, cause);
 * }
 * }</pre>
 * 第一次出现会完整打印；随后被抑制的次数会累计，并在下一次放行时附在日志末尾，
 * 因此日志不会「安静地丢东西」——你能看到一共抑制了多少条。
 */
public final class LogThrottle {

    private LogThrottle() {
    }

    /** 每个 key 的限流状态。key 都是写死的常量字符串，所以这个 map 的大小是有界的。 */
    private static final ConcurrentHashMap<String, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        private long nextAllowedAt;
        private final AtomicLong suppressed = new AtomicLong();
    }

    /**
     * 询问这个 key 现在是否可以打日志，并占用一个名额。
     *
     * @param key            限流分组标识（同一类日志用同一个 key）
     * @param intervalMillis 最小打印间隔
     * @return {@code null} 表示本次应当<b>整条跳过</b>（已被限流）；
     *         否则返回一个可直接拼到日志消息末尾的后缀字符串（首次为空串，
     *         之后为「 (同类日志已抑制 N 条)」）。
     */
    public static String acquire(String key, long intervalMillis) {
        long now = System.currentTimeMillis();
        State state = STATES.computeIfAbsent(key, ignored -> new State());
        synchronized (state) {
            if (now < state.nextAllowedAt) {
                state.suppressed.incrementAndGet();
                return null;
            }
            state.nextAllowedAt = now + intervalMillis;
            long suppressed = state.suppressed.getAndSet(0L);
            return suppressed == 0L ? "" : " (同类日志已抑制 " + suppressed + " 条)";
        }
    }
}
