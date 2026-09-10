package moe.koseirin.nyanruaineo.Minecraft.netty;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.Getter;
import lombok.Setter;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Direction;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolData;

import java.util.List;

/**
 * 这个解码器会根据当前协议状态是否认识这个数据包 ID 来做出不同处理：
 * 如果认识，就把帧里的有效数据解析成一个 {@link DefinedPacket} 对象；
 * 如果不认识，就不做任何改动，直接把原始字节流透传下去（这种透传模式主要用在游戏阶段，用来实现数据透明转发）。
 */
public class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PacketDecoder.class);

    @Setter
    @Getter
    private Protocol protocol;
    private final Direction direction;
    // Starts at 0 (not -1) so the Handshake packet — decoded before the real protocol version is
    // known — still matches the all-version packet registrations.
    @Setter
    @Getter
    private int protocolVersion = 0;

    public PacketDecoder(Protocol protocol, Direction direction) {
        this.protocol = protocol;
        this.direction = direction;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        int packetId;
        try {
            packetId = DefinedPacket.readVarInt(in);
        } catch (Exception ex) {
            in.resetReaderIndex();
            return;
        }

        // Single O(1) lookup: decode when registered, otherwise relay the frame verbatim.
        ProtocolData.Entry entry = protocol.getData(direction).getEntry(packetId, protocolVersion);
        if (entry != null) {
            try {
                DefinedPacket packet = entry.constructor().get();
                packet.read(in, direction, protocolVersion);
                // A packet may move the connection to the next protocol phase (e.g. LoginAcknowledged
                // → CONFIGURATION, FinishConfiguration → GAME), mirroring BungeeCord's nextProtocol().
                Protocol next = packet.nextProtocol();
                if (next != null) {
                    Protocol from = protocol;
                    protocol = next;
                    if (log.isDebugEnabled()) {
                        log.debug("{} decode {} -> {} after {} (v{})", direction, from, next,
                                packet.getClass().getSimpleName(), protocolVersion);
                    }
                }
                out.add(packet);
            } catch (Exception ex) {
                // A proxy must never be stricter than the endpoints: if our codec cannot decode a
                // packet (unexpected format, oversized payload, version drift), relay the raw frame
                // so the client/backend — which know the exact format — can handle it. This keeps
                // high-version Forge/NeoForge mod traffic (config snapshots, custom payloads, ...)
                // flowing even when the local protocol table is incomplete.
                in.resetReaderIndex();
                out.add(in.retain());
                // 注意：这条日志本身也要先判级别。slf4j 的占位符虽然延迟格式化，但变参数组
                // 和装箱在关闭日志时依然会发生，而这是一条「解码失败就会走到」的热路径。
                if (log.isDebugEnabled()) {
                    log.debug("decode failed for {} packet 0x{} ({}); relaying raw", direction,
                            Integer.toHexString(packetId), ex.toString());
                }
            }
        } else {
            in.resetReaderIndex();
            out.add(in.retain());
        }
    }

}
