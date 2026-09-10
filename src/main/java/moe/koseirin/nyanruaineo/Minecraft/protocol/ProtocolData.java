package moe.koseirin.nyanruaineo.Minecraft.protocol;

/*
 * @author KoseiRin_
 * awa
 */

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 单个 {@link Protocol} 阶段和 {@link Direction} 的数据包注册表。
 * 数据包可注册为适用于所有协议版本或某个版本范围。
 * <p>
 * 查找按<b>每个协议版本各缓存一份</b>不可变表（连接在握手后其协议版本不再改变），
 * 因此热路径上的解码/编码只是一次 {@link ConcurrentHashMap} 查找，为 O(1) 且无锁。
 * <p>
 * 曾经这里是一个「单个槽位」的缓存（{@code cachedVersion} + 两个 volatile 表字段），
 * 那在高并发下有致命问题：
 * <ul>
 *     <li>混合客户端版本（例如 1.8 和 1.21 同时在线，公开代理的常态）会让缓存每次都失效，
 *         于是<b>每个数据包</b>都要在 {@code synchronized} 方法里重建两张表 —— 实测
 *         单版本 0.5~8 ns/次查找，混合版本 260~385 ns/次，还额外产生大量 HashMap 垃圾
 *         并让所有 worker 线程在这把全局锁上排队；</li>
 *     <li>更糟的是它是错的：读取方先读 volatile 的 {@code cachedVersion}、再读 volatile 的表字段，
 *         两次读之间不是原子的，而写入方是「先发布表、再发布版本号」。于是查找可能拿到
 *         <b>另一个协议版本的表</b>，把数据包解码成错误的类。</li>
 * </ul>
 * 现在每个版本的表只构建一次、一次性发布为不可变对象，两个问题同时消失。
 */
public final class ProtocolData {

    private static final int ANY_VERSION = Integer.MAX_VALUE;

    private final List<Entry> entries = new ArrayList<>();

    /**
     * 按协议版本缓存的两张表。{@link #register} 只在枚举类的静态初始化期间被调用，
     * 而类初始化对外部线程是可见且唯一的，所以第一次查找时 {@link #entries} 必然已经是最终内容，
     * 缓存不会被注册过程污染。
     */
    private final ConcurrentHashMap<Integer, Tables> tablesByVersion = new ConcurrentHashMap<>();

    public record Entry(int minVersion, int maxVersion, int packetId,
                        Class<? extends DefinedPacket> packetClass,
                        Supplier<? extends DefinedPacket> constructor) {
    }

    /** 某个协议版本对应的两张查找表；构建完成后不再修改。 */
    private record Tables(Map<Integer, Entry> byId,
                          Map<Class<? extends DefinedPacket>, Integer> byClass) {
    }

    /** Registers a packet for every protocol version. */
    public <T extends DefinedPacket> void register(int packetId, Class<T> packetClass, Supplier<T> constructor) {
        register(0, ANY_VERSION, packetId, packetClass, constructor);
    }

    /** Registers a packet for the inclusive protocol version range. */
    public <T extends DefinedPacket> void register(int minVersion, int maxVersion, int packetId,
                                                   Class<T> packetClass, Supplier<T> constructor) {
        entries.add(new Entry(minVersion, maxVersion, packetId, packetClass, constructor));
    }

    private static boolean inRange(Entry entry, int protocolVersion) {
        // A negative (unknown) version matches any registration: the handshake packet is decoded
        // before the real protocol version is known.
        return protocolVersion < 0
                || (protocolVersion >= entry.minVersion && protocolVersion <= entry.maxVersion);
    }

    /** O(1) lookup of the registration for the given id and version, or {@code null}. */
    public Entry getEntry(int packetId, int protocolVersion) {
        return tables(protocolVersion).byId().get(packetId);
    }

    /**
     * Creates a fresh packet instance for the given id and protocol version, or {@code null} when
     * the id is not registered in this state/direction/version (the decoder then falls back to raw
     * passthrough).
     */
    public DefinedPacket createPacket(int packetId, int protocolVersion) {
        Entry entry = getEntry(packetId, protocolVersion);
        return entry == null ? null : entry.constructor().get();
    }

    public boolean hasPacket(int packetId, int protocolVersion) {
        return getEntry(packetId, protocolVersion) != null;
    }

    public int getId(Class<? extends DefinedPacket> packetClass, int protocolVersion) {
        Integer packetId = tables(protocolVersion).byClass().get(packetClass);
        if (packetId == null) {
            throw new IllegalArgumentException("Packet not registered for this state/direction/version: "
                    + packetClass.getSimpleName() + " (" + protocolVersion + ")");
        }
        return packetId;
    }

    /**
     * 该协议版本的两张表；每个版本最多构建一次。构建一次约 300 ns，
     * 而查找此后再也不需要加锁或分配内存。
     */
    private Tables tables(int protocolVersion) {
        // 热路径：无锁命中。每个版本只需构建一次，命中之后再也不会分配内存或加锁。
        Tables cached = tablesByVersion.get(protocolVersion);
        if (cached != null) {
            return cached;
        }
        return tablesByVersion.computeIfAbsent(protocolVersion, version -> {
            Map<Integer, Entry> byId = new HashMap<>();
            Map<Class<? extends DefinedPacket>, Integer> byClass = new HashMap<>();
            for (Entry entry : entries) {
                if (inRange(entry, version)) {
                    byId.putIfAbsent(entry.packetId(), entry);
                    byClass.putIfAbsent(entry.packetClass(), entry.packetId());
                }
            }
            // 不可变 + 一次性发布：不会有「版本号已更新、表还是旧的」这种撕裂读。
            return new Tables(Map.copyOf(byId), Map.copyOf(byClass));
        });
    }
}
