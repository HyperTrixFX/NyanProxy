package moe.koseirin.nyanruaineo.Minecraft.protocol;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 每个 Minecraft 协议数据包的基类。
 * <p>
 * 子类只需实现带版本参数的 {@link #read(ByteBuf, int)} 和 {@link #write(ByteBuf, int)} 方法；
 * 不带版本参数的方法会委托给它们，以便调用方可以任意选用。
 */
public abstract class DefinedPacket {

    public static void writeVarInt(int value, ByteBuf output) {
        do {
            byte temp = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                temp |= (byte) 0x80;
            }
            output.writeByte(temp);
        } while (value != 0);
    }

    /** 单个数组/字符串解码的上限（2 MiB），防止恶意声明超大长度导致 OOM。 */
    private static final int MAX_READ_LENGTH = 2 * 1024 * 1024;

    public static int readVarInt(ByteBuf input) {
        int result = 0;
        int shift = 0;
        byte b;
        do {
            if (shift >= 35) {
                throw new IllegalArgumentException("VarInt too big");
            }
            b = input.readByte();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * 读取一个 VarInt，最多消耗 {@code maxBytes} 个字节（该变体用于 FML 模组列表，因为其计数字段必须保持很小）。
     */
    public static int readVarInt(ByteBuf input, int maxBytes) {
        int out = 0;
        int bytes = 0;
        byte in;
        while (true) {
            in = input.readByte();
            out |= (in & 0x7F) << (bytes++ * 7);
            if (bytes > maxBytes) {
                throw new RuntimeException("VarInt too big");
            }
            if ((in & 0x80) != 0x80) {
                break;
            }
        }
        return out;
    }

    /**
     * 读取位于 {@code readerIndex} 的 VarInt 但<b>不移动</b>读取指针，也不分配任何对象。
     * <p>
     * 热路径上（每个数据包都要判断一次包 ID）不能用 {@code buf.duplicate()} + {@code readVarInt}
     * 那种写法：那会给每个数据包都额外分配一个 ByteBuf 包装对象。这个版本直接用
     * {@link ByteBuf#getByte(int)} 按下标窥视，只有真正需要解析时才做复制。
     */
    public static int peekVarInt(ByteBuf input) {
        int readerIndex = input.readerIndex();
        int readable = input.readableBytes();
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (shift / 7 >= readable) {
                throw new IllegalArgumentException("VarInt truncated");
            }
            byte b = input.getByte(readerIndex + shift / 7);
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalArgumentException("VarInt too big");
    }

    public static void writeString(String value, ByteBuf output) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length, output);
        output.writeBytes(bytes);
    }

    public static String readString(ByteBuf input) {
        byte[] bytes = readArray(input);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 读取一个字符串，其 UTF-8 字节长度和解码后的字符数量均受限制
     * （该方法用于旧版插件频道名称）。
     */
    public static String readString(ByteBuf input, int maxLen) {
        int length = readVarInt(input);
        if (length > maxLen * 4) {
            throw new IllegalArgumentException("Cannot receive string longer than " + maxLen * 4 + " bytes");
        }
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (value.length() > maxLen) {
            throw new IllegalArgumentException("Cannot receive string longer than " + maxLen);
        }
        return value;
    }

    public static void writeArray(byte[] value, ByteBuf output) {
        writeVarInt(value.length, output);
        output.writeBytes(value);
    }

    public static byte[] readArray(ByteBuf input) {
        int length = readVarInt(input);
        if (length < 0 || length > MAX_READ_LENGTH || length > input.readableBytes()) {
            throw new IllegalArgumentException("Array length out of bounds: " + length);
        }
        byte[] bytes = new byte[length];
        input.readBytes(bytes);
        return bytes;
    }

    public static void writeUUID(UUID value, ByteBuf output) {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    public static UUID readUUID(ByteBuf input) {
        return new UUID(input.readLong(), input.readLong());
    }

    /** Copies every remaining byte of the buffer into a fresh array, mirroring BungeeCord. */
    public static byte[] toArray(ByteBuf buf) {
        byte[] ret = new byte[buf.readableBytes()];
        buf.readBytes(ret);
        return ret;
    }

    /**
     * 从缓冲区解码此数据包，并携带该数据包的传输方向。默认实现忽略方向；
     * 那些数据包格式依赖于方向的数据包会重写此方法。
     */
    public void read(ByteBuf buf, Direction direction, int protocolVersion) {
        read(buf, protocolVersion);
    }

    /**
     * 从缓冲区解码此数据包。提供协议版本，以便版本相关的数据包可以切换其网络格式。
     */
    public void read(ByteBuf buf, int protocolVersion) {
        throw new UnsupportedOperationException("Packet must implement read");
    }

    public void read(ByteBuf buf) {
        read(buf, -1);
    }

    /**
     * 将此数据包编码到缓冲区。
     */
    public void write(ByteBuf buf, int protocolVersion) {
        throw new UnsupportedOperationException("Packet must implement write");
    }

    public void write(ByteBuf buf) {
        write(buf, -1);
    }

    /**
     * 该数据包在解码/编码完成后会将连接转移到的协议阶段；若为 {@code null} 则表示保持当前阶段不变。
     * （例如 LoginAcknowledged / StartConfiguration → CONFIGURATION，
     * FinishConfiguration → GAME）。解码器/编码器会自动应用此阶段切换。
     */
    public Protocol nextProtocol() {
        return null;
    }
}
