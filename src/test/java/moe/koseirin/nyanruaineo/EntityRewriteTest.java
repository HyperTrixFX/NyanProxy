package moe.koseirin.nyanruaineo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.EntityRewrite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实体 ID 改写的回归测试。
 * <p>
 * 背景：1.16 之前的服务器切换不转发 JoinGame，客户端保留旧实体 ID，后端用新 ID。代理必须在所有
 * "按实体寻址"的裸帧里互译这两个 ID，否则击退（Entity Velocity）和受击效果会被客户端丢弃。
 * 另外帧缓冲是固定容量的切片，实体 ID 的 VarInt 变长时必须先换成可增长缓冲——否则会抛
 * {@code IndexOutOfBoundsException} 把玩家踢下线。
 */
class EntityRewriteTest {

    /** 读取"首字段即实体 ID"的帧里的实体 ID。 */
    private static int entityId(ByteBuf frame) {
        ByteBuf dup = frame.duplicate();
        DefinedPacket.readVarInt(dup); // packet id
        return DefinedPacket.readVarInt(dup);
    }

    private static ByteBuf velocity(int entityId) {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x12, buf); // Entity Velocity (1.8)
        DefinedPacket.writeVarInt(entityId, buf);
        buf.writeShort(1).writeShort(2).writeShort(3);
        return buf;
    }

    @Test
    void versionMappingCoversLegacyOnly() {
        assertTrue(EntityRewrite.forVersion(47) != null, "1.8 must have a rewrite map");
        assertTrue(EntityRewrite.forVersion(107) != null, "1.9 must have a rewrite map");
        assertTrue(EntityRewrite.forVersion(578) != null, "1.15.2 must have a rewrite map");
        assertNull(EntityRewrite.forVersion(735), "1.16+ forwards JoinGame, so it needs no rewrite");
    }

    @Test
    void clientboundVelocityIsTranslatedToTheClientId() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = velocity(500);
        rewrite.rewriteClientbound(packet, 500, 1000);
        assertEquals(1000, entityId(packet));
        packet.release();
    }

    @Test
    void unrelatedEntityIdsAreLeftAlone() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = velocity(777);
        rewrite.rewriteClientbound(packet, 500, 1000);
        assertEquals(777, entityId(packet), "another entity's id must never be touched");
        packet.release();
    }

    @Test
    void serverboundUseEntityIsTranslatedToTheBackendId() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x02, packet); // Use Entity (1.8)
        DefinedPacket.writeVarInt(1000, packet);
        DefinedPacket.writeVarInt(1, packet);    // attack
        rewrite.rewriteServerbound(packet, 1000, 500);
        assertEquals(500, entityId(packet));
        packet.release();
    }

    @Test
    void entityStatusUsesTheFixedWidthIntPath() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x1A, packet); // Entity Status (1.8): int id + byte status
        packet.writeInt(500);
        packet.writeByte(2);                     // hurt
        rewrite.rewriteClientbound(packet, 500, 1000);

        ByteBuf dup = packet.duplicate();
        DefinedPacket.readVarInt(dup);
        assertEquals(1000, dup.readInt());
        assertEquals(2, dup.readUnsignedByte());
        packet.release();
    }

    @Test
    void growingVarIntKeepsTheRestOfTheFrameIntact() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = velocity(127); // 1-byte VarInt
        rewrite.rewriteClientbound(packet, 127, 128); // 2-byte VarInt

        ByteBuf dup = packet.duplicate();
        DefinedPacket.readVarInt(dup);
        assertEquals(128, DefinedPacket.readVarInt(dup));
        assertEquals(1, dup.readShort());
        assertEquals(2, dup.readShort());
        assertEquals(3, dup.readShort());
        packet.release();
    }

    @Test
    void shrinkingVarIntKeepsTheRestOfTheFrameIntact() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf packet = velocity(128); // 2-byte VarInt
        rewrite.rewriteClientbound(packet, 128, 127); // 1-byte VarInt

        ByteBuf dup = packet.duplicate();
        DefinedPacket.readVarInt(dup);
        assertEquals(127, DefinedPacket.readVarInt(dup));
        assertEquals(1, dup.readShort());
        packet.release();
    }

    @Test
    void setPassengersRewritesOnlyThePassengerArray() {
        EntityRewrite rewrite = EntityRewrite.forVersion(107); // 1.9
        ByteBuf packet = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x40, packet); // Set Passengers
        DefinedPacket.writeVarInt(999, packet);  // vehicle, unrelated
        DefinedPacket.writeVarInt(2, packet);    // count
        DefinedPacket.writeVarInt(500, packet);  // the player
        DefinedPacket.writeVarInt(777, packet);  // unrelated
        rewrite.rewriteClientbound(packet, 500, 1000);

        ByteBuf dup = packet.duplicate();
        DefinedPacket.readVarInt(dup);
        assertEquals(999, DefinedPacket.readVarInt(dup));
        assertEquals(2, DefinedPacket.readVarInt(dup));
        assertEquals(1000, DefinedPacket.readVarInt(dup));
        assertEquals(777, DefinedPacket.readVarInt(dup));
        packet.release();
    }

    @Test
    void destroyEntitiesRewritesItsIdArray() {
        EntityRewrite rewrite = EntityRewrite.forVersion(107); // 1.9
        ByteBuf packet = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x30, packet); // Destroy Entities
        DefinedPacket.writeVarInt(1, packet);
        DefinedPacket.writeVarInt(500, packet);
        rewrite.rewriteClientbound(packet, 500, 1000);

        ByteBuf dup = packet.duplicate();
        DefinedPacket.readVarInt(dup);
        assertEquals(1, DefinedPacket.readVarInt(dup));
        assertEquals(1000, DefinedPacket.readVarInt(dup));
        packet.release();
    }

    /**
     * 帧解码器（{@code readRetainedSlice}）和解压器（{@code wrappedBuffer(byte[])}）产出的都是
     * 固定容量缓冲；实体 ID 的 VarInt 变长时原地改写会溢出。这条测试锁死了"先换成可增长缓冲"的行为。
     */
    @Test
    void fixedCapacityFrameIsDetectedAndCopied() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        byte[] frame = {0x0B, 100, 0, 0}; // Entity Action (1.8): id + entityId + action + jumpBoost
        ByteBuf fixed = Unpooled.wrappedBuffer(frame);

        assertEquals(fixed.capacity(), fixed.maxCapacity(), "wrappedBuffer is fixed-capacity");
        assertTrue(rewrite.isRewritableServerbound(fixed));

        ByteBuf grown = EntityRewrite.growable(fixed);
        assertNotSame(fixed, grown, "a fixed-capacity frame must be copied");
        fixed.release();

        rewrite.rewriteServerbound(grown, 100, 300); // 1-byte -> 2-byte VarInt
        assertEquals(300, entityId(grown));
        grown.release();
    }

    @Test
    void alreadyGrowableBufferIsReusedInPlace() {
        ByteBuf growable = Unpooled.buffer(64);
        assertSame(growable, EntityRewrite.growable(growable), "a growable buffer needs no copy");
        growable.release();
    }

    @Test
    void nonEntityPacketsAreNeverRewrittenOrCopied() {
        EntityRewrite rewrite = EntityRewrite.forVersion(47);
        ByteBuf chunk = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x21, chunk); // 1.8 chunk data
        chunk.writeZero(64);
        assertFalse(rewrite.isRewritableClientbound(chunk), "chunk data must not be copied");
        assertFalse(rewrite.isRewritableServerbound(chunk), "chunk data must not be copied");
        chunk.release();
    }
}
