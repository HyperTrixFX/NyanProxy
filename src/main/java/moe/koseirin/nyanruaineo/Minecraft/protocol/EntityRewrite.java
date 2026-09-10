package moe.koseirin.nyanruaineo.Minecraft.protocol;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * 代理玩家"自身实体 ID"的裸帧改写器。
 * <p>
 * 1.16 之前的服务器切换走"旧式 respawn 舞步"，不会把新后端的 JoinGame 转发给客户端，
 * 所以客户端一直保留着第一次登录时分配的实体 ID（{@code clientEntityId}），而新后端会分配一个
 * 全新的实体 ID（{@code serverEntityId}）。如果不做翻译，所有"按实体寻址"的数据包
 * （Entity Velocity 击退、Entity Status 受击、Entity Metadata、移动/传送、装备、动画、药水效果……）
 * 携带的都是客户端不认识的 ID，客户端会静默丢弃——表现为"能扣血（Update Health 不含实体 ID），
 * 但没有击退和受击效果"。这里把这两套 ID 在所有实体寻址包中互译。
 * <p>
 * 只改写数据包的首个实体 ID 字段。BungeeCord 额外改写的两处这里有意省略：
 * <ul>
 * <li>离线模式 UUID（SpawnPlayer / Spectate）——本代理走 online-mode + IP 转发，UUID 原样保留；</li>
 * <li>EntityMetadata 内部的嵌套实体 ID（鱼钩 / 烟花 / 守卫者光束）——需要解析 NBT/物品槽，
 * 原版 PVP 路径用不到。</li>
 * </ul>
 */
public class EntityRewrite {

    private final boolean[] clientboundInts = new boolean[256];
    private final boolean[] clientboundVarInts = new boolean[256];
    private final boolean[] serverboundInts = new boolean[256];
    private final boolean[] serverboundVarInts = new boolean[256];

    /**
     * 只由版本专属特例改写的包（Destroy Entities / Combat Event / Entity Sound Effect）。
     * 它们不能进上面那几张通用表——否则通用逻辑会把它们的首字段（数量 / 事件类型）误当成实体 ID。
     */
    private final boolean[] clientboundSpecial = new boolean[256];
    private final boolean[] serverboundSpecial = new boolean[256];

    private static final EntityRewrite REWRITE_1_8 = new EntityRewrite_1_8();
    private static final EntityRewrite REWRITE_1_9 = new EntityRewrite_1_9();
    private static final EntityRewrite REWRITE_1_9_4 = new EntityRewrite_1_9_4();
    private static final EntityRewrite REWRITE_1_12 = new EntityRewrite_1_12();
    private static final EntityRewrite REWRITE_1_12_1 = new EntityRewrite_1_12_1();
    private static final EntityRewrite REWRITE_1_13 = new EntityRewrite_1_13();
    private static final EntityRewrite REWRITE_1_14 = new EntityRewrite_1_14();
    private static final EntityRewrite REWRITE_1_15 = new EntityRewrite_1_15();

    /**
     * 返回该协议版本对应的实体 ID 改写器；1.16+（735）的切换会转发 JoinGame，
     * 客户端重新学习实体 ID，无需改写，返回 {@code null}。
     */
    public static EntityRewrite forVersion(int version) {
        if (version == ProtocolConstants.MINECRAFT_1_8) {
            return REWRITE_1_8;
        }
        if (version == 107 || version == 108 || version == 109) {          // 1.9-1.9.2
            return REWRITE_1_9;
        }
        if (version == 110 || version == 210 || version == 315 || version == 316) { // 1.9.4 / 1.10 / 1.11
            return REWRITE_1_9_4;
        }
        if (version == ProtocolConstants.MINECRAFT_1_12) {
            return REWRITE_1_12;
        }
        if (version == ProtocolConstants.MINECRAFT_1_12_1 || version == 340) {
            return REWRITE_1_12_1;
        }
        if (version == ProtocolConstants.MINECRAFT_1_13 || version == 401 || version == 404) {
            return REWRITE_1_13;
        }
        if (version == ProtocolConstants.MINECRAFT_1_14 || version == 480 || version == 485
                || version == 490 || version == 498) {
            return REWRITE_1_14;
        }
        if (version == ProtocolConstants.MINECRAFT_1_15 || version == 575 || version == 578) {
            return REWRITE_1_15;
        }
        return null;
    }

    protected void addRewrite(int id, Direction direction, boolean varint) {
        if (direction == Direction.TO_CLIENT) {
            if (varint) {
                clientboundVarInts[id] = true;
            } else {
                clientboundInts[id] = true;
            }
        } else if (varint) {
            serverboundVarInts[id] = true;
        } else {
            serverboundInts[id] = true;
        }
    }

    /** 服务端→客户端：把新后端的实体 ID 翻译回客户端保留的稳定 ID。 */
    public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
        rewrite(packet, serverEntityId, clientEntityId, clientboundInts, clientboundVarInts);
    }

    /** 客户端→服务端：把客户端保留的稳定 ID 翻译成当前后端使用的 ID。 */
    public void rewriteServerbound(ByteBuf packet, int clientEntityId, int serverEntityId) {
        rewrite(packet, clientEntityId, serverEntityId, serverboundInts, serverboundVarInts);
    }

    /**
     * 登记一个"只由版本专属特例改写"的包（Destroy Entities / Combat Event / Entity Sound Effect），
     * 让 {@link #isRewritableClientbound} 能识别它——这类包不能进通用重写表，否则首字段会被误改。
     */
    protected void markSpecial(int id, Direction direction) {
        if (direction == Direction.TO_CLIENT) {
            clientboundSpecial[id] = true;
        } else {
            serverboundSpecial[id] = true;
        }
    }

    /** 这一帧是否携带代理需要改写的实体 ID（据此决定是否值得先换成可增长缓冲）。 */
    public boolean isRewritableClientbound(ByteBuf packet) {
        int packetId = peekPacketId(packet);
        return packetId >= 0 && packetId < 256
                && (clientboundInts[packetId] || clientboundVarInts[packetId] || clientboundSpecial[packetId]);
    }

    /** 与 {@link #isRewritableClientbound} 对应的客户端→服务端方向。 */
    public boolean isRewritableServerbound(ByteBuf packet) {
        int packetId = peekPacketId(packet);
        return packetId >= 0 && packetId < 256
                && (serverboundInts[packetId] || serverboundVarInts[packetId] || serverboundSpecial[packetId]);
    }

    private static int peekPacketId(ByteBuf packet) {
        try {
            return DefinedPacket.readVarInt(packet.duplicate());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 返回一个可以安全扩容的帧缓冲。帧解码器（{@code readRetainedSlice}）和解压器
     * （{@code wrappedBuffer(byte[])}）产出的都是<b>固定容量</b>的缓冲：实体 ID 的 VarInt 在
     * 客户端与后端长度不同时（例如 100 ↔ 300）改写会让整帧变长，原地改写就会抛
     * {@code IndexOutOfBoundsException} 把玩家踢下线。这种帧必须先复制到可增长的堆缓冲上。
     *
     * @return 原缓冲（本身已可增长）或它的可增长副本；返回副本时调用方必须 release 原缓冲
     */
    public static ByteBuf growable(ByteBuf packet) {
        if (packet.maxCapacity() > packet.capacity()) {
            return packet;
        }
        ByteBuf out = Unpooled.buffer(packet.capacity() + 16);
        out.writeBytes(packet, 0, packet.writerIndex());
        out.writerIndex(packet.writerIndex());
        out.readerIndex(packet.readerIndex());
        return out;
    }

    protected static void rewriteInt(ByteBuf packet, int oldId, int newId, int offset) {
        int readId = packet.getInt(offset);
        if (readId == oldId) {
            packet.setInt(offset, newId);
        } else if (readId == newId) {
            packet.setInt(offset, oldId);
        }
    }

    protected static void rewriteVarInt(ByteBuf packet, int oldId, int newId, int offset) {
        int readId = DefinedPacket.readVarInt(packet);
        if (readId == oldId || readId == newId) {
            ByteBuf data = packet.copy();
            packet.readerIndex(offset);
            packet.writerIndex(offset);
            DefinedPacket.writeVarInt(readId == oldId ? newId : oldId, packet);
            packet.writeBytes(data);
            data.release();
        }
    }

    /** 改写"首个字段即实体 ID"的数据包（int 或 VarInt），具体由各版本的重写表决定。 */
    private static void rewrite(ByteBuf packet, int oldId, int newId, boolean[] ints, boolean[] varints) {
        int readerIndex = packet.readerIndex();
        int packetId = DefinedPacket.readVarInt(packet);
        int packetIdLength = packet.readerIndex() - readerIndex;
        if (ints[packetId]) {
            rewriteInt(packet, oldId, newId, readerIndex + packetIdLength);
        } else if (varints[packetId]) {
            rewriteVarInt(packet, oldId, newId, readerIndex + packetIdLength);
        }
        packet.readerIndex(readerIndex);
    }

    /** 1.8 / 1.8.9（协议 47）。 */
    private static final class EntityRewrite_1_8 extends EntityRewrite {

        EntityRewrite_1_8() {
            markSpecial(0x13, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x42, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x04, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x0A, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x0B, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x0C, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x0D, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x0E, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x0F, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x10, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x11, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x12, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x14, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x15, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x16, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x17, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x18, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x19, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x1A, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x1C, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x1D, Direction.TO_CLIENT, true); // Entity Effect
            addRewrite(0x1E, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x20, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x25, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x2C, Direction.TO_CLIENT, true); // Spawn Global Entity
            addRewrite(0x43, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x49, Direction.TO_CLIENT, true); // Update Entity NBT

            addRewrite(0x02, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x0B, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            if (packetId == 0x0D /* Collect Item */) {
                DefinedPacket.readVarInt(packet);
                rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
            } else if (packetId == 0x1B /* Attach Entity */) {
                rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
            } else if (packetId == 0x13 /* Destroy Entities */) {
                rewriteIdArray(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength);
            } else if (packetId == 0x0E /* Spawn Object */) {
                DefinedPacket.readVarInt(packet);
                int type = packet.readUnsignedByte();
                if (type == 60 || type == 90) {
                    rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 14, false);
                }
            } else if (packetId == 0x42 /* Combat Event */) {
                rewriteCombatEvent(packet, serverEntityId, clientEntityId);
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.9 / 1.9.1 / 1.9.2（协议 107-109）。 */
    private static final class EntityRewrite_1_9 extends EntityRewrite {

        EntityRewrite_1_9() {
            markSpecial(0x30, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x2C, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x25, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x26, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x27, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x2F, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x31, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x34, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x36, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x39, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x3A, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x3B, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x3C, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x40, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x49, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x4A, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x4B, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x4C, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0A, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x14, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x3A -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x49 -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x40 -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x30 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = packet.readUnsignedByte();
                    if (type == 60 || type == 90 || type == 91) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 60 || type == 91);
                    }
                }
                case 0x2C -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.9.4 / 1.10 / 1.11（协议 110 / 210 / 315-316）。 */
    private static final class EntityRewrite_1_9_4 extends EntityRewrite {

        EntityRewrite_1_9_4() {
            markSpecial(0x30, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x2C, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x25, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x26, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x27, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x2F, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x31, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x34, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x36, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x39, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x3A, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x3B, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x3C, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x40, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x48, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x49, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x4A, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x4B, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0A, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x14, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x3A -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x48 -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x40 -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x30 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = packet.readUnsignedByte();
                    if (type == 60 || type == 90 || type == 91) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 60 || type == 91);
                    }
                }
                case 0x2C -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.12（协议 335）。 */
    private static final class EntityRewrite_1_12 extends EntityRewrite {

        EntityRewrite_1_12() {
            markSpecial(0x31, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x2C, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x25, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x26, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x27, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x2F, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x32, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x35, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x38, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x3B, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x3C, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x3D, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x3E, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x42, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x4A, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x4B, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x4D, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x4E, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0B, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x15, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x3C -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x4A -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x42 -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x31 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = packet.readUnsignedByte();
                    if (type == 60 || type == 90 || type == 91) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 60 || type == 91);
                    }
                }
                case 0x2C -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.12.1 / 1.12.2（协议 338 / 340）。 */
    private static final class EntityRewrite_1_12_1 extends EntityRewrite {

        EntityRewrite_1_12_1() {
            markSpecial(0x32, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x2D, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x25, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x26, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x27, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x30, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x33, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x36, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x39, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x3C, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x3D, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x3E, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x3F, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x43, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x4B, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x4C, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x4E, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x4F, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0A, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x15, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x3D -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x4B -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x43 -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x32 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = packet.readUnsignedByte();
                    if (type == 60 || type == 90 || type == 91) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 60 || type == 91);
                    }
                }
                case 0x2D -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.13 / 1.13.1 / 1.13.2（协议 393 / 401 / 404）。 */
    private static final class EntityRewrite_1_13 extends EntityRewrite {

        EntityRewrite_1_13() {
            markSpecial(0x35, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x2F, Direction.TO_CLIENT); // Combat Event
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1C, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x27, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x29, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x2A, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x33, Direction.TO_CLIENT, true); // Use bed
            addRewrite(0x36, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x39, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x3C, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x3F, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x40, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x41, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x42, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x46, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x4F, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x50, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x52, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x53, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0D, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x19, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x40 -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x4F -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x46 -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x35 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = packet.readUnsignedByte();
                    if (type == 60 || type == 90 || type == 91) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 60 || type == 91);
                    }
                }
                case 0x2F -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.14 / 1.14.1-1.14.4（协议 477 / 480 / 485 / 490 / 498）。 */
    private static final class EntityRewrite_1_14 extends EntityRewrite {

        EntityRewrite_1_14() {
            markSpecial(0x37, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x32, Direction.TO_CLIENT); // Combat Event
            markSpecial(0x50, Direction.TO_CLIENT); // Entity Sound Effect
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x08, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1B, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x28, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x29, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x2A, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x2B, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x38, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x3B, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x3E, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x43, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x44, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x45, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x46, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x4A, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x55, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x56, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x58, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x59, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0E, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x1B, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x44 -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x55 -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x4A -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x37 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = DefinedPacket.readVarInt(packet);
                    if (type == 2 || type == 101 || type == 71) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 2 || type == 71);
                    }
                }
                case 0x32 -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                case 0x50 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /** 1.15 / 1.15.1 / 1.15.2（协议 573 / 575 / 578）。 */
    private static final class EntityRewrite_1_15 extends EntityRewrite {

        EntityRewrite_1_15() {
            markSpecial(0x38, Direction.TO_CLIENT); // Destroy Entities
            markSpecial(0x33, Direction.TO_CLIENT); // Combat Event
            markSpecial(0x51, Direction.TO_CLIENT); // Entity Sound Effect
            addRewrite(0x00, Direction.TO_CLIENT, true); // Spawn Object
            addRewrite(0x01, Direction.TO_CLIENT, true); // Spawn Experience Orb
            addRewrite(0x03, Direction.TO_CLIENT, true); // Spawn Mob
            addRewrite(0x04, Direction.TO_CLIENT, true); // Spawn Painting
            addRewrite(0x05, Direction.TO_CLIENT, true); // Spawn Player
            addRewrite(0x06, Direction.TO_CLIENT, true); // Animation
            addRewrite(0x09, Direction.TO_CLIENT, true); // Block Break Animation
            addRewrite(0x1C, Direction.TO_CLIENT, false); // Entity Status
            addRewrite(0x29, Direction.TO_CLIENT, true); // Entity Relative Move
            addRewrite(0x2A, Direction.TO_CLIENT, true); // Entity Look and Relative Move
            addRewrite(0x2B, Direction.TO_CLIENT, true); // Entity Look
            addRewrite(0x2C, Direction.TO_CLIENT, true); // Entity
            addRewrite(0x39, Direction.TO_CLIENT, true); // Remove Entity Effect
            addRewrite(0x3C, Direction.TO_CLIENT, true); // Entity Head Look
            addRewrite(0x3F, Direction.TO_CLIENT, true); // Camera
            addRewrite(0x44, Direction.TO_CLIENT, true); // Entity Metadata
            addRewrite(0x45, Direction.TO_CLIENT, false); // Attach Entity
            addRewrite(0x46, Direction.TO_CLIENT, true); // Entity Velocity
            addRewrite(0x47, Direction.TO_CLIENT, true); // Entity Equipment
            addRewrite(0x4B, Direction.TO_CLIENT, true); // Set Passengers
            addRewrite(0x56, Direction.TO_CLIENT, true); // Collect Item
            addRewrite(0x57, Direction.TO_CLIENT, true); // Entity Teleport
            addRewrite(0x59, Direction.TO_CLIENT, true); // Entity Properties
            addRewrite(0x5A, Direction.TO_CLIENT, true); // Entity Effect

            addRewrite(0x0E, Direction.TO_SERVER, true); // Use Entity
            addRewrite(0x1B, Direction.TO_SERVER, true); // Entity Action
        }

        @Override
        public void rewriteClientbound(ByteBuf packet, int serverEntityId, int clientEntityId) {
            super.rewriteClientbound(packet, serverEntityId, clientEntityId);

            int readerIndex = packet.readerIndex();
            int packetId = DefinedPacket.readVarInt(packet);
            int packetIdLength = packet.readerIndex() - readerIndex;
            int jumpIndex = packet.readerIndex();
            switch (packetId) {
                case 0x45 -> rewriteInt(packet, serverEntityId, clientEntityId, readerIndex + packetIdLength + 4);
                case 0x56 -> {
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                case 0x4B -> {
                    DefinedPacket.readVarInt(packet);
                    jumpIndex = packet.readerIndex();
                    rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                }
                case 0x38 -> rewriteIdArray(packet, serverEntityId, clientEntityId, jumpIndex);
                case 0x00 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readUUID(packet);
                    int type = DefinedPacket.readVarInt(packet);
                    if (type == 2 || type == 102 || type == 72) {
                        rewriteSpawnObjectData(packet, serverEntityId, clientEntityId, 26, type == 2 || type == 72);
                    }
                }
                case 0x33 -> rewriteCombatEvent(packet, serverEntityId, clientEntityId);
                case 0x51 -> {
                    DefinedPacket.readVarInt(packet);
                    DefinedPacket.readVarInt(packet);
                    rewriteVarInt(packet, serverEntityId, clientEntityId, packet.readerIndex());
                }
                default -> {
                }
            }
            packet.readerIndex(readerIndex);
        }
    }

    /**
     * 改写 Destroy Entities / Set Passengers 里的实体 ID 数组。
     * {@code offset} 是数组长度字段的起始位置（两者在 Set Passengers 场景下会先跳过首个实体 ID）。
     */
    private static void rewriteIdArray(ByteBuf packet, int oldId, int newId, int offset) {
        int count = DefinedPacket.readVarInt(packet);
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = DefinedPacket.readVarInt(packet);
        }
        packet.readerIndex(offset);
        packet.writerIndex(offset);
        DefinedPacket.writeVarInt(count, packet);
        for (int id : ids) {
            if (id == oldId) {
                id = newId;
            } else if (id == newId) {
                id = oldId;
            }
            DefinedPacket.writeVarInt(id, packet);
        }
    }

    /**
     * 改写 Spawn Object（箭 / 鱼竿 / 光谱箭）里 data 字段携带的射击者实体 ID。
     * {@code dataOffset} 是 data 字段相对实体 ID 起始处的字节偏移（1.8 为 14，1.9+ 为 26）；
     * {@code plusOne} 表示该类型的 data 存的是"射击者 ID + 1"（箭/光谱箭）。
     */
    private static void rewriteSpawnObjectData(ByteBuf packet, int oldId, int newId, int dataOffset, boolean plusOne) {
        if (plusOne) {
            oldId = oldId + 1;
            newId = newId + 1;
        }
        packet.skipBytes(dataOffset);
        int position = packet.readerIndex();
        int readId = packet.readInt();
        int changedId = readId;
        if (readId == oldId) {
            packet.setInt(position, changedId = newId);
        } else if (readId == newId) {
            packet.setInt(position, changedId = oldId);
        }
        if (readId > 0 && changedId <= 0) {
            packet.writerIndex(packet.writerIndex() - 6);
        } else if (changedId > 0 && readId <= 0) {
            packet.ensureWritable(6);
            packet.writerIndex(packet.writerIndex() + 6);
        }
    }

    /** 改写 Combat Event 里的实体 ID（结束战斗 / 实体死亡）。 */
    private static void rewriteCombatEvent(ByteBuf packet, int oldId, int newId) {
        int event = packet.readUnsignedByte();
        if (event == 1) {
            DefinedPacket.readVarInt(packet);
            rewriteInt(packet, oldId, newId, packet.readerIndex());
        } else if (event == 2) {
            int position = packet.readerIndex();
            rewriteVarInt(packet, oldId, newId, packet.readerIndex());
            packet.readerIndex(position);
            DefinedPacket.readVarInt(packet);
            rewriteInt(packet, oldId, newId, packet.readerIndex());
        }
    }
}
