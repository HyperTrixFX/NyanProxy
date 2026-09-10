package moe.koseirin.nyanruaineo.Minecraft.protocol;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProtocolData} 的按版本缓存：混合客户端版本同时在线时（1.8 + 1.21，公开代理的常态），
 * 查找必须<b>每个版本各自命中自己的那张表</b>，既不重复构建，也绝不能互相串味。
 * <p>
 * 这里同时锁住两个回归点：
 * <ul>
 *     <li>以前的「单槽位缓存」在版本交替时会反复重建，且两次 volatile 读之间存在竞态，
 *         可能返回另一个协议版本的表（把包解成错误的类）；</li>
 *     <li>跨版本并发查找的结果必须始终等于单线程下算出来的正确答案。</li>
 * </ul>
 */
class ProtocolDataCacheTest {

    private static final int V_1_8 = 47;
    private static final int V_1_9 = 107;
    private static final int V_1_12 = 335;
    private static final int V_1_20_5 = 766;
    private static final int V_1_21_1 = 767;

    /** 一张干净的注册表，避免依赖 Protocol 里具体的包 ID 分配。 */
    private static ProtocolData freshRegistry() {
        ProtocolData data = new ProtocolData();
        data.register(0x01, PacketA.class, PacketA::new);
        data.register(0x02, PacketB.class, PacketB::new);
        // 只对 1.8 有效的包，用来验证版本隔离。
        data.register(0, V_1_8, 0x10, PacketLegacyOnly.class, PacketLegacyOnly::new);
        // 只对 1.21.1 有效的包，ID 与上面那个旧版包相同：按版本必须解析成不同的类。
        data.register(V_1_21_1, Integer.MAX_VALUE, 0x10, PacketModernOnly.class, PacketModernOnly::new);
        return data;
    }

    static class PacketA extends DefinedPacket {
    }

    static class PacketB extends DefinedPacket {
    }

    static class PacketLegacyOnly extends DefinedPacket {
    }

    static class PacketModernOnly extends DefinedPacket {
    }

    @Test
    void eachVersionKeepsItsOwnTable() {
        ProtocolData data = freshRegistry();

        assertSame(PacketLegacyOnly.class, entry(data, 0x10, V_1_8));
        assertSame(PacketModernOnly.class, entry(data, 0x10, V_1_21_1));
        // 再来一轮：版本交替不得让结果发生任何变化。
        for (int i = 0; i < 100; i++) {
            assertSame(PacketLegacyOnly.class, entry(data, 0x10, V_1_8));
            assertSame(PacketModernOnly.class, entry(data, 0x10, V_1_21_1));
        }
    }

    @Test
    void sameVersionIsBuiltOnceAndReused() {
        ProtocolData data = freshRegistry();
        ProtocolData.Entry first = data.getEntry(0x01, V_1_20_5);
        assertNotNull(first);
        // 同一个版本重复查找必须返回同一个 Entry 实例（说明表没有被重建）。
        for (int i = 0; i < 1000; i++) {
            assertSame(first, data.getEntry(0x01, V_1_20_5));
        }
    }

    @Test
    void versionOutOfRangeHasNoEntry() {
        ProtocolData data = freshRegistry();
        // 旧版专有包在 1.21.1 上不存在，反之亦然。
        assertNull(data.getEntry(0x10, V_1_9));
        assertNull(data.getEntry(0x10, V_1_12));
        // 全版本注册的包在任意版本都在。
        assertNotNull(data.getEntry(0x01, V_1_9));
        assertNotNull(data.getEntry(0x01, V_1_21_1));
    }

    @Test
    void unregisteredClassStillThrows() {
        ProtocolData data = freshRegistry();
        assertThrows(IllegalArgumentException.class, () -> data.getId(PacketLegacyOnly.class, V_1_21_1));
        assertEquals(0x10, data.getId(PacketLegacyOnly.class, V_1_8));
    }

    /**
     * 并发地以 4 个不同协议版本交替查找，结果必须始终等于单线程基准答案。
     * 旧实现下这里会随机拿到别的版本的表（撕裂读），因此会失败。
     */
    @Test
    void concurrentMixedVersionsNeverReturnAnotherVersionsTable() throws Exception {
        ProtocolData data = freshRegistry();
        int[] versions = {V_1_8, V_1_9, V_1_12, V_1_21_1};

        // 单线程基准答案。
        Map<String, Class<?>> expected = new HashMap<>();
        for (int version : versions) {
            for (int id = 0; id < 0x20; id++) {
                ProtocolData.Entry entry = data.getEntry(id, version);
                expected.put(version + ":" + id, entry == null ? null : entry.packetClass());
            }
        }

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<String> mismatch = new AtomicReference<>();
        AtomicBoolean running = new AtomicBoolean(true);
        for (int t = 0; t < threads; t++) {
            final int offset = t;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 60_000 && mismatch.get() == null; i++) {
                        int version = versions[(i + offset) & 3];
                        int id = i & 0x1F;
                        ProtocolData.Entry entry = data.getEntry(id, version);
                        Class<?> actual = entry == null ? null : entry.packetClass();
                        Class<?> want = expected.get(version + ":" + id);
                        if (actual != want) {
                            mismatch.compareAndSet(null, "version " + version + " id 0x"
                                    + Integer.toHexString(id) + " -> " + actual + ", expected " + want);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "concurrent lookups did not finish");
        running.set(false);
        assertNull(mismatch.get(), "lookup returned another protocol version's table: " + mismatch.get());
    }

    private static Class<?> entry(ProtocolData data, int id, int version) {
        ProtocolData.Entry entry = data.getEntry(id, version);
        return entry == null ? null : entry.packetClass();
    }
}
