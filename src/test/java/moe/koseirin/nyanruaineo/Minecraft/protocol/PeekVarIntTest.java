package moe.koseirin.nyanruaineo.Minecraft.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DefinedPacket#peekVarInt} 是数据包热路径上「只看一眼包 ID」的入口：
 * 它必须在不移动 readerIndex、不分配任何包装对象的前提下给出和 {@code readVarInt} 相同的结果。
 */
class PeekVarIntTest {

    private static ByteBuf varint(int value) {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt(value, buf);
        return buf;
    }

    @Test
    void matchesReadVarIntForEveryWidth() {
        int[] values = {0, 1, 0x7F, 0x80, 0xFF, 0x100, 300, 0x3FFF, 0x4000, 0x0FFFFFFF, Integer.MAX_VALUE};
        for (int value : values) {
            ByteBuf buf = varint(value);
            try {
                int readerIndex = buf.readerIndex();
                assertEquals(value, DefinedPacket.peekVarInt(buf), "peek of " + value);
                // 关键点：窥视之后读取指针必须原地不动（否则后续解析会从错误的位置开始）。
                assertEquals(readerIndex, buf.readerIndex(), "peek must not move readerIndex (" + value + ")");
                assertEquals(value, DefinedPacket.readVarInt(buf), "read of " + value);
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void peekDoesNotMoveReaderIndexOnAPartiallyReadBuffer() {
        ByteBuf buf = Unpooled.buffer();
        try {
            DefinedPacket.writeVarInt(0x11, buf);
            DefinedPacket.writeVarInt(0x22, buf);
            // 先正常读掉第一个，再从当前位置窥视第二个。
            assertEquals(0x11, DefinedPacket.readVarInt(buf));
            int readerIndex = buf.readerIndex();
            assertEquals(0x22, DefinedPacket.peekVarInt(buf));
            assertEquals(readerIndex, buf.readerIndex());
            // 窥视之后仍然能正常读出来。
            assertEquals(0x22, DefinedPacket.readVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void trailingBytesAreLeftUntouched() {
        // 真实场景：原始帧 = [包 ID VarInt][payload]，窥视包 ID 后 payload 必须完好。
        ByteBuf buf = Unpooled.buffer();
        try {
            DefinedPacket.writeVarInt(0x40, buf);
            buf.writeBytes(new byte[]{1, 2, 3, 4});
            assertEquals(0x40, DefinedPacket.peekVarInt(buf));
            assertEquals(5, buf.readableBytes());
            assertEquals(0x40, DefinedPacket.readVarInt(buf));
            assertEquals(4, buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    @Test
    void truncatedVarIntIsRejectedInsteadOfReadingPastTheEnd() {
        // 只写了续接位却没有后续字节：必须抛错，而不是越界读到别的数据。
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(0x80);
            assertThrows(IllegalArgumentException.class, () -> DefinedPacket.peekVarInt(buf));
            assertEquals(0, buf.readerIndex());
        } finally {
            buf.release();
        }
    }

    @Test
    void oversizedVarIntIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < 6; i++) {
                buf.writeByte(0x80);
            }
            assertThrows(IllegalArgumentException.class, () -> DefinedPacket.peekVarInt(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void emptyBufferIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> DefinedPacket.peekVarInt(buf));
        } finally {
            buf.release();
        }
    }
}
