package moe.koseirin.nyanruaineo.Minecraft.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogThrottle} 必须做到两件事：压制同一个 key 的重复日志，但绝不「安静地丢东西」——
 * 下一次放行时要能看到一共抑制了多少条。
 */
class LogThrottleTest {

    @Test
    void firstCallIsAllowedWithoutSuffix() {
        assertEquals("", LogThrottle.acquire("test-first-" + System.nanoTime(), 60_000L));
    }

    @Test
    void repeatCallsWithinTheIntervalAreSuppressed() {
        String key = "test-suppress-" + System.nanoTime();
        assertNotNull(LogThrottle.acquire(key, 60_000L));
        for (int i = 0; i < 5; i++) {
            assertNull(LogThrottle.acquire(key, 60_000L), "repeat call " + i + " must be suppressed");
        }
    }

    @Test
    void suppressedCountIsReportedOnTheNextAllowedLine() throws Exception {
        String key = "test-count-" + System.nanoTime();
        assertNotNull(LogThrottle.acquire(key, 30L));   // 放行，下一个放行点是 30ms 之后
        for (int i = 0; i < 3; i++) {
            assertNull(LogThrottle.acquire(key, 30L), "call " + i + " must be suppressed");
        }
        Thread.sleep(80L);
        String suffix = LogThrottle.acquire(key, 30L);  // 间隔已过：放行并汇报抑制数量
        assertNotNull(suffix);
        assertTrue(suffix.contains("3"), "suffix should report 3 suppressed lines but was: " + suffix);
    }

    @Test
    void intervalExpiryAllowsTheNextLine() throws Exception {
        String key = "test-expiry-" + System.nanoTime();
        assertNotNull(LogThrottle.acquire(key, 200L));
        assertNull(LogThrottle.acquire(key, 200L));
        Thread.sleep(300L);
        assertNotNull(LogThrottle.acquire(key, 200L));
    }

    @Test
    void differentKeysDoNotShareState() {
        String a = "test-key-a-" + System.nanoTime();
        String b = "test-key-b-" + System.nanoTime();
        assertNotNull(LogThrottle.acquire(a, 60_000L));
        // 另一个 key 不该被 a 的限流影响。
        assertNotNull(LogThrottle.acquire(b, 60_000L));
        assertNull(LogThrottle.acquire(a, 60_000L));
        assertNull(LogThrottle.acquire(b, 60_000L));
    }
}
