package moe.koseirin.nyanruaineo.Minecraft.netty;

/*
 * @author KoseiRin_
 * awa
 */

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.ReferenceCountUtil;
import lombok.Getter;
import lombok.Setter;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Direction;
import moe.koseirin.nyanruaineo.Minecraft.protocol.Protocol;

import java.util.List;

/**
 * 这个编码器的作用是把一个 {@link DefinedPacket} 对象编码成符合协议的帧数据，
 * 但如果传入的本身就是原始 {@link ByteBuf}，那就什么都不做，直接放行。
 */
public class PacketEncoder extends MessageToMessageEncoder<Object> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PacketEncoder.class);

    @Setter
    @Getter
    private Protocol protocol;
    private final Direction direction;
    // Starts at 0 (not -1) so packets encoded before the handshake is read still resolve their ids.
    @Setter
    @Getter
    private int protocolVersion = 0;

    public PacketEncoder(Protocol protocol, Direction direction) {
        this.protocol = protocol;
        this.direction = direction;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
        if (msg instanceof DefinedPacket packet) {
            ByteBuf buf = ctx.alloc().buffer();
            int packetId = protocol.getId(direction, packet.getClass(), protocolVersion);
            DefinedPacket.writeVarInt(packetId, buf);
            packet.write(buf, protocolVersion);
            out.add(buf);
            // A packet may move the connection to the next protocol phase (e.g. StartConfiguration
            // → CONFIGURATION, FinishConfiguration → GAME), mirroring BungeeCord's nextProtocol().
            Protocol next = packet.nextProtocol();
            if (next != null) {
                Protocol from = protocol;
                protocol = next;
                if (log.isDebugEnabled()) {
                    log.debug("{} encode {} -> {} after {} (v{})", direction, from, next,
                            packet.getClass().getSimpleName(), protocolVersion);
                }
            }
        } else {
            out.add(ReferenceCountUtil.retain(msg));
        }
    }

}
