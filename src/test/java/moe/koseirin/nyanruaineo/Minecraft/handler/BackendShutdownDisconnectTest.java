package moe.koseirin.nyanruaineo.Minecraft.handler;

import com.alibaba.fastjson2.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import moe.koseirin.nyanruaineo.Minecraft.protocol.DefinedPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.ProtocolConstants;
import moe.koseirin.nyanruaineo.Minecraft.util.ChatComponentUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后端<b>正常关服</b>（Spigot 的 {@code settings.shutdown-message}，默认 "Server closed"）时，
 * 后端会先发一个游戏阶段 Disconnect 包，客户端收到后自己断开——代理必须认出这个包，才能把玩家
 * 送回大厅；而插件/管理员的主动踢人必须原样转发，让玩家看到后端给的原因。
 */
class BackendShutdownDisconnectTest {

    /** 旧版（&lt; 1.20.3）的游戏阶段 Disconnect 载荷 = [包 ID][JSON 字符串]。 */
    private static ByteBuf legacyDisconnect(int protocolVersion, String jsonReason) {
        ByteBuf buf = Unpooled.buffer();
        DefinedPacket.writeVarInt(ProtocolConstants.disconnectPacketId(protocolVersion), buf);
        DefinedPacket.writeString(jsonReason, buf);
        return buf;
    }

    @Test
    void shutdownMessageIsRecognised() {
        ByteBuf frame = legacyDisconnect(47, "{\"text\":\"Server closed\"}");
        assertTrue(DownstreamBridge.isBackendShutdownDisconnect(frame, 47));
        frame.release();
    }

    @Test
    void translationKeyIsRecognised() {
        ByteBuf frame = legacyDisconnect(47, "{\"translate\":\"multiplayer.disconnect.server_shutdown\"}");
        assertTrue(DownstreamBridge.isBackendShutdownDisconnect(frame, 47));
        frame.release();
    }

    @Test
    void chineseShutdownMessageIsRecognised() {
        ByteBuf frame = legacyDisconnect(47, "{\"text\":\"服务器已关闭\"}");
        assertTrue(DownstreamBridge.isBackendShutdownDisconnect(frame, 47));
        frame.release();
    }

    @Test
    void deliberateKickIsNotIntercepted() {
        ByteBuf frame = legacyDisconnect(47, "{\"text\":\"You are banned from this server\",\"color\":\"red\"}");
        assertFalse(DownstreamBridge.isBackendShutdownDisconnect(frame, 47),
                "a plugin/admin kick must keep showing the backend's own message");
        frame.release();
    }

    @Test
    void unrelatedPacketIsNotIntercepted() {
        ByteBuf frame = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x12, frame); // Entity Velocity, not a disconnect
        DefinedPacket.writeVarInt(1, frame);
        frame.writeShort(0).writeShort(0).writeShort(0);
        assertFalse(DownstreamBridge.isBackendShutdownDisconnect(frame, 47));
        frame.release();
    }

    @Test
    void malformedPayloadIsNeverTreatedAsShutdown() {
        ByteBuf frame = Unpooled.buffer();
        DefinedPacket.writeVarInt(0x40, frame); // disconnect id, but the reason is missing
        assertFalse(DownstreamBridge.isBackendShutdownDisconnect(frame, 47));
        frame.release();
    }

    @Test
    void modernNbtReasonIsRecognised() {
        JSONObject component = new JSONObject();
        component.put("text", "Server closed");
        ByteBuf frame = Unpooled.buffer();
        DefinedPacket.writeVarInt(ProtocolConstants.disconnectPacketId(766), frame);
        frame.writeBytes(ChatComponentUtils.writeNbtComponentBytes(component));
        assertTrue(DownstreamBridge.isBackendShutdownDisconnect(frame, 766));
        frame.release();
    }

    /** 锁死按版本区分的游戏阶段 Disconnect 包 ID（与 BungeeCord 的 Kick 映射一致）。 */
    @Test
    void packetIdsMatchTheKnownMappings() {
        assertEquals(0x40, ProtocolConstants.disconnectPacketId(47));  // 1.8-1.8.9
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(107)); // 1.9
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(340)); // 1.12.2
        assertEquals(0x1B, ProtocolConstants.disconnectPacketId(393)); // 1.13
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(477)); // 1.14
        assertEquals(0x1B, ProtocolConstants.disconnectPacketId(573)); // 1.15
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(735)); // 1.16
        assertEquals(0x19, ProtocolConstants.disconnectPacketId(751)); // 1.16.2
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(758)); // 1.18.2
        assertEquals(0x17, ProtocolConstants.disconnectPacketId(759)); // 1.19
        assertEquals(0x1A, ProtocolConstants.disconnectPacketId(763)); // 1.20.1
        assertEquals(0x1B, ProtocolConstants.disconnectPacketId(764)); // 1.20.2
        assertEquals(0x1D, ProtocolConstants.disconnectPacketId(766)); // 1.20.5
        assertEquals(0x1C, ProtocolConstants.disconnectPacketId(770)); // 1.21.5
        assertEquals(0x20, ProtocolConstants.disconnectPacketId(773)); // 1.21.9
    }
}
