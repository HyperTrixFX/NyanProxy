package moe.koseirin.nyanruaineo.Minecraft.protocol;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.BossBar;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Chat;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientChat;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientCommand;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ClientSettings;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EncryptionRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EncryptionResponse;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.EntityStatus;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ForgeHandshake;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.FinishConfiguration;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.GameState;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Handshake;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.JoinGame;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Kick;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginAcknowledged;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginSuccess;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PingPacket;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoRemove;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerInfoUpdate;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PlayerListItem;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.PluginMessage;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Respawn;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ScoreboardObjective;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ScoreboardScore;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.SetCompression;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StartConfiguration;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StatusRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.StatusResponse;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabListHeaderFooter;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.TabCompleteRequest;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.Team;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.UnsignedClientCommand;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.ViewDistance;

/**
 * 这个类定义了 Minecraft 的协议状态。
 * 每个状态下都维护了两份协议数据注册表，分别对应客户端↔服务端两个方向。
 * <p>
 * 在 GAME（游戏）状态下，代理只注册那些它需要插手的数据包，比如命令聊天、TabList、切换服务器流程，以及插件消息频道。
 * 游戏阶段里其他的数据包，代理不会去解析，直接当成原始字节流转发，这样代理就能兼容不同版本的 Minecraft。
 * 至于 CONFIGURATION（配置）状态（1.20.2 开始才有），代理只注册了用来推动阶段切换和插件消息频道的数据包，
 * 这样一来，NeoForge 或 Fabric 在配置阶段的握手就能完好无损地穿透代理。
 */
public enum Protocol {

    HANDSHAKE,
    STATUS,
    LOGIN,
    CONFIGURATION,
    GAME;

    private final ProtocolData toServer = new ProtocolData();
    private final ProtocolData toClient = new ProtocolData();

    static {
        // Handshake state
        HANDSHAKE.toServer.register(0x00, Handshake.class, Handshake::new);
        // Pre-1.8 Forge (FML 1.7) handshake packet (0x250), both directions.
        HANDSHAKE.toServer.register(0x250, ForgeHandshake.class, ForgeHandshake::new);
        HANDSHAKE.toClient.register(0x250, ForgeHandshake.class, ForgeHandshake::new);

        // Status state
        STATUS.toServer.register(0x00, StatusRequest.class, StatusRequest::new);
        STATUS.toServer.register(0x01, PingPacket.class, PingPacket::new);
        STATUS.toClient.register(0x00, StatusResponse.class, StatusResponse::new);
        STATUS.toClient.register(0x01, PingPacket.class, PingPacket::new);

        // Login state
        LOGIN.toServer.register(0x00, LoginRequest.class, LoginRequest::new);
        LOGIN.toServer.register(0x01, EncryptionResponse.class, EncryptionResponse::new);
        LOGIN.toClient.register(0x00, Kick.class, Kick::new);
        LOGIN.toClient.register(0x01, EncryptionRequest.class, EncryptionRequest::new);
        LOGIN.toClient.register(0x02, LoginSuccess.class, LoginSuccess::new);
        LOGIN.toClient.register(0x03, SetCompression.class, SetCompression::new);
        // Pre-1.8 Forge clients may send the FML handshake (0x250) after switching to login.
        LOGIN.toServer.register(0x250, ForgeHandshake.class, ForgeHandshake::new);
        LOGIN.toClient.register(0x250, ForgeHandshake.class, ForgeHandshake::new);
        // 1.20.2+: the client acknowledges Login Success with this before entering configuration.
        LOGIN.toServer.register(ProtocolConstants.MINECRAFT_1_20_2, Integer.MAX_VALUE, 0x03,
                LoginAcknowledged.class, LoginAcknowledged::new);

        // Configuration state (1.20.2+): the phase between Login Success and Join Game, where
        // NeoForge/Fabric run their handshake. Only the plugin-message channel and the phase
        // transition are registered; the rest (registry data, resource packs, keep-alive, ...) is
        // relayed verbatim. BungeeCord CONFIGURATION mappings.
        CONFIGURATION.toClient.register(ProtocolConstants.MINECRAFT_1_20_2, 765, 0x00,
                PluginMessage.class, PluginMessage::new);
        CONFIGURATION.toClient.register(766, Integer.MAX_VALUE, 0x01,
                PluginMessage.class, PluginMessage::new);
        CONFIGURATION.toClient.register(ProtocolConstants.MINECRAFT_1_20_2, 765, 0x02,
                FinishConfiguration.class, FinishConfiguration::new);
        CONFIGURATION.toClient.register(766, Integer.MAX_VALUE, 0x03,
                FinishConfiguration.class, FinishConfiguration::new);

        CONFIGURATION.toServer.register(ProtocolConstants.MINECRAFT_1_20_2, Integer.MAX_VALUE, 0x00,
                ClientSettings.class, ClientSettings::new);
        CONFIGURATION.toServer.register(ProtocolConstants.MINECRAFT_1_20_2, 765, 0x01,
                PluginMessage.class, PluginMessage::new);
        CONFIGURATION.toServer.register(766, Integer.MAX_VALUE, 0x02,
                PluginMessage.class, PluginMessage::new);
        CONFIGURATION.toServer.register(ProtocolConstants.MINECRAFT_1_20_2, 765, 0x02,
                FinishConfiguration.class, FinishConfiguration::new);
        CONFIGURATION.toServer.register(766, Integer.MAX_VALUE, 0x03,
                FinishConfiguration.class, FinishConfiguration::new);

        // Game state: the serverbound chat/command packets, whose ids shifted across versions
        // (mirroring BungeeCord's Chat/ClientCommand/ClientChat registrations). They are decoded so
        // the proxy can intercept commands; everything else is relayed raw.
        GAME.toServer.register(0, 47, 0x01, Chat.class, Chat::new);          // 1.7.10-1.8.9
        GAME.toServer.register(107, 316, 0x02, Chat.class, Chat::new);       // 1.9-1.11.2
        GAME.toServer.register(335, 335, 0x03, Chat.class, Chat::new);       // 1.12
        GAME.toServer.register(338, 404, 0x02, Chat.class, Chat::new);       // 1.12.1-1.13.2
        GAME.toServer.register(477, 758, 0x03, Chat.class, Chat::new);       // 1.14-1.18.2

        // ClientSettings (serverbound "Client Settings", 1.8-1.20.1): the last field is skinParts
        // (cape + second skin layer). Tracked so a server switch can replay it to the new backend —
        // BungeeCord parity (UpstreamBridge.handle(ClientSettings) -> con.setSettings). The 1.20.2+
        // configuration-phase variant is registered in the CONFIGURATION state below.
        GAME.toServer.register(47, 106, 0x15, ClientSettings.class, ClientSettings::new);   // 1.8-1.8.9
        GAME.toServer.register(107, 334, 0x04, ClientSettings.class, ClientSettings::new);  // 1.9-1.11.2
        GAME.toServer.register(335, 337, 0x05, ClientSettings.class, ClientSettings::new);  // 1.12
        GAME.toServer.register(338, 476, 0x04, ClientSettings.class, ClientSettings::new);  // 1.12.1-1.13.2
        GAME.toServer.register(477, 758, 0x05, ClientSettings.class, ClientSettings::new);  // 1.14-1.18.2
        GAME.toServer.register(759, 759, 0x07, ClientSettings.class, ClientSettings::new);  // 1.19
        GAME.toServer.register(760, 760, 0x08, ClientSettings.class, ClientSettings::new);  // 1.19.1-1.19.2
        GAME.toServer.register(761, 761, 0x07, ClientSettings.class, ClientSettings::new);  // 1.19.3
        GAME.toServer.register(762, 763, 0x08, ClientSettings.class, ClientSettings::new);  // 1.19.4-1.20.1

        GAME.toServer.register(759, 759, 0x03, ClientCommand.class, ClientCommand::new);   // 1.19
        GAME.toServer.register(760, 765, 0x04, ClientCommand.class, ClientCommand::new);   // 1.19.1-1.20.4
        GAME.toServer.register(766, 767, 0x05, ClientCommand.class, ClientCommand::new);   // 1.20.5-1.21.1
        GAME.toServer.register(768, 770, 0x06, ClientCommand.class, ClientCommand::new);   // 1.21.2-1.21.5
        GAME.toServer.register(771, 774, 0x07, ClientCommand.class, ClientCommand::new);   // 1.21.6-1.21.11
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x08, ClientCommand.class, ClientCommand::new); // 26.1+

        // 1.20.5+ unsigned chat command (BungeeCord UnsignedClientCommand): sent when the client
        // cannot sign the command (not in its tree), so it must be intercepted the same way.
        GAME.toServer.register(766, 767, 0x04, UnsignedClientCommand.class, UnsignedClientCommand::new);
        GAME.toServer.register(768, 770, 0x05, UnsignedClientCommand.class, UnsignedClientCommand::new);
        GAME.toServer.register(771, 774, 0x06, UnsignedClientCommand.class, UnsignedClientCommand::new);
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x07, UnsignedClientCommand.class, UnsignedClientCommand::new);

        // 1.19.3+ tab-completion request (BungeeCord TabCompleteRequest) — intercepted so the proxy
        // answers completion for its own commands instead of forwarding to the backend.
        GAME.toServer.register(761, 761, 0x08, TabCompleteRequest.class, TabCompleteRequest::new);   // 1.19.3
        GAME.toServer.register(762, 763, 0x09, TabCompleteRequest.class, TabCompleteRequest::new);   // 1.19.4-1.20.1
        GAME.toServer.register(764, 765, 0x0A, TabCompleteRequest.class, TabCompleteRequest::new);   // 1.20.2-1.20.4
        GAME.toServer.register(766, 767, 0x0B, TabCompleteRequest.class, TabCompleteRequest::new);
        GAME.toServer.register(768, 770, 0x0D, TabCompleteRequest.class, TabCompleteRequest::new);
        GAME.toServer.register(771, 774, 0x0E, TabCompleteRequest.class, TabCompleteRequest::new);
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x0F, TabCompleteRequest.class, TabCompleteRequest::new);

        GAME.toServer.register(759, 759, 0x04, ClientChat.class, ClientChat::new);        // 1.19
        GAME.toServer.register(760, 765, 0x05, ClientChat.class, ClientChat::new);        // 1.19.1-1.20.4
        GAME.toServer.register(766, 767, 0x06, ClientChat.class, ClientChat::new);        // 1.20.5-1.21.1
        GAME.toServer.register(768, 770, 0x07, ClientChat.class, ClientChat::new);        // 1.21.2-1.21.5
        GAME.toServer.register(771, 774, 0x08, ClientChat.class, ClientChat::new);        // 1.21.6-1.21.11
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x09, ClientChat.class, ClientChat::new); // 26.1+

        // Game state, clientbound: the TabList packets the proxy intercepts and modifies
        // (mirroring BungeeCord's PlayerListItem / PlayerListHeaderFooter registrations).
        // PlayerListItem is the legacy 1.8-1.18.2 format; the 1.19+ player info packets and the
        // 1.20.3+ NBT header/footer are relayed raw.
        GAME.toClient.register(47, 106, 0x38, PlayerListItem.class, PlayerListItem::new);        // 1.8-1.8.9
        GAME.toClient.register(107, 337, 0x2D, PlayerListItem.class, PlayerListItem::new);       // 1.9-1.12
        GAME.toClient.register(338, 392, 0x2E, PlayerListItem.class, PlayerListItem::new);       // 1.12.1-1.12.2
        GAME.toClient.register(393, 476, 0x30, PlayerListItem.class, PlayerListItem::new);       // 1.13-1.13.2
        GAME.toClient.register(477, 572, 0x33, PlayerListItem.class, PlayerListItem::new);       // 1.14-1.14.4
        GAME.toClient.register(573, 734, 0x34, PlayerListItem.class, PlayerListItem::new);       // 1.15-1.15.2
        GAME.toClient.register(735, 750, 0x33, PlayerListItem.class, PlayerListItem::new);       // 1.16-1.16.1
        GAME.toClient.register(751, 754, 0x32, PlayerListItem.class, PlayerListItem::new);       // 1.16.2-1.16.5
        GAME.toClient.register(755, 758, 0x36, PlayerListItem.class, PlayerListItem::new);       // 1.17-1.18.2

        // 1.19.3+ player info packets (BungeeCord PlayerInfoUpdate / PlayerInfoRemove mappings).
        // 761-764 carry a JSON display name; 765+ encodes it as an anonymous NBT component, which
        // PlayerInfoUpdate handles for both forms.
        GAME.toClient.register(761, 761, 0x36, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.19.3
        GAME.toClient.register(762, 763, 0x3A, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.19.4-1.20.1
        GAME.toClient.register(764, 765, 0x3C, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.20.2-1.20.4
        GAME.toClient.register(766, 767, 0x3E, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.20.5-1.21.1
        GAME.toClient.register(768, 769, 0x40, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.21.2-1.21.4
        GAME.toClient.register(770, 773, 0x3F, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.21.5-1.21.8
        GAME.toClient.register(774, 774, 0x44, PlayerInfoUpdate.class, PlayerInfoUpdate::new);   // 1.21.9-1.21.11
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x46, PlayerInfoUpdate.class, PlayerInfoUpdate::new); // 26.1+

        GAME.toClient.register(761, 761, 0x35, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.19.3
        GAME.toClient.register(762, 763, 0x39, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.19.4-1.20.1
        GAME.toClient.register(764, 765, 0x3B, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.20.2-1.20.4
        GAME.toClient.register(766, 767, 0x3D, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.20.5-1.21.1
        GAME.toClient.register(768, 769, 0x3F, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.21.2-1.21.4
        GAME.toClient.register(770, 773, 0x3E, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.21.5-1.21.8
        GAME.toClient.register(774, 774, 0x43, PlayerInfoRemove.class, PlayerInfoRemove::new);    // 1.21.9-1.21.11
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x45, PlayerInfoRemove.class, PlayerInfoRemove::new); // 26.1+

        // JoinGame (opaque payload), registered so the server connector can detect the world
        // reset during login and relay it to the client first (BungeeCord cut-through).
        GAME.toClient.register(0, 106, 0x01, JoinGame.class, JoinGame::new);            // 1.7.10-1.8.9
        GAME.toClient.register(107, 392, 0x23, JoinGame.class, JoinGame::new);          // 1.9-1.12.2
        GAME.toClient.register(393, 572, 0x25, JoinGame.class, JoinGame::new);          // 1.13-1.14.4
        GAME.toClient.register(573, 734, 0x26, JoinGame.class, JoinGame::new);          // 1.15-1.15.2
        GAME.toClient.register(735, 750, 0x25, JoinGame.class, JoinGame::new);          // 1.16-1.16.1
        GAME.toClient.register(751, 754, 0x24, JoinGame.class, JoinGame::new);          // 1.16.2-1.16.5
        GAME.toClient.register(755, 758, 0x26, JoinGame.class, JoinGame::new);          // 1.17-1.18.2
        GAME.toClient.register(759, 759, 0x23, JoinGame.class, JoinGame::new);          // 1.19
        GAME.toClient.register(760, 760, 0x25, JoinGame.class, JoinGame::new);          // 1.19.1-1.19.2
        GAME.toClient.register(761, 761, 0x24, JoinGame.class, JoinGame::new);          // 1.19.3
        GAME.toClient.register(762, 763, 0x28, JoinGame.class, JoinGame::new);          // 1.19.4-1.20.1
        // 1.20.2+ JoinGame (BungeeCord Login): the dimension registry NBT is gone, the dimension
        // is a String (1.20.2-1.20.4) or a VarInt id (1.20.5+); parsed so the switch flow works
        // and the packet re-encodes byte-perfectly.
        GAME.toClient.register(764, 765, 0x29, JoinGame.class, JoinGame::new);          // 1.20.2-1.20.4
        GAME.toClient.register(766, 767, 0x2B, JoinGame.class, JoinGame::new);          // 1.20.5-1.21.1
        GAME.toClient.register(768, 769, 0x2C, JoinGame.class, JoinGame::new);          // 1.21.2-1.21.4
        GAME.toClient.register(770, 772, 0x2B, JoinGame.class, JoinGame::new);          // 1.21.5-1.21.8
        GAME.toClient.register(773, 774, 0x30, JoinGame.class, JoinGame::new);          // 1.21.9-1.21.11
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x31, JoinGame.class, JoinGame::new); // 26.1+

        // Respawn (mirrors BungeeCord's registrations; pre-1.16 ids too, for the switch dance).
        GAME.toClient.register(47, 106, 0x07, Respawn.class, Respawn::new);             // 1.8-1.8.9
        GAME.toClient.register(107, 334, 0x33, Respawn.class, Respawn::new);            // 1.9-1.11.2
        GAME.toClient.register(335, 337, 0x34, Respawn.class, Respawn::new);            // 1.12
        GAME.toClient.register(338, 392, 0x35, Respawn.class, Respawn::new);            // 1.12.1-1.12.2
        GAME.toClient.register(393, 476, 0x38, Respawn.class, Respawn::new);            // 1.13-1.13.2
        GAME.toClient.register(477, 572, 0x3A, Respawn.class, Respawn::new);            // 1.14-1.14.4
        GAME.toClient.register(573, 734, 0x3B, Respawn.class, Respawn::new);            // 1.15-1.15.2
        GAME.toClient.register(735, 750, 0x3A, Respawn.class, Respawn::new);            // 1.16-1.16.1
        GAME.toClient.register(751, 754, 0x39, Respawn.class, Respawn::new);            // 1.16.2-1.16.5
        GAME.toClient.register(755, 758, 0x3D, Respawn.class, Respawn::new);            // 1.17-1.18.2
        GAME.toClient.register(759, 759, 0x3B, Respawn.class, Respawn::new);            // 1.19
        GAME.toClient.register(760, 760, 0x3E, Respawn.class, Respawn::new);            // 1.19.1-1.19.2
        GAME.toClient.register(761, 761, 0x3D, Respawn.class, Respawn::new);            // 1.19.3
        GAME.toClient.register(762, 763, 0x41, Respawn.class, Respawn::new);            // 1.19.4-1.20.1
        // 1.20.2+ Respawn (copy-meta moved to the end; VarInt dimension from 1.20.5; sea level from 1.21.2).
        GAME.toClient.register(764, 764, 0x43, Respawn.class, Respawn::new);            // 1.20.2
        GAME.toClient.register(765, 765, 0x45, Respawn.class, Respawn::new);            // 1.20.3-1.20.4
        GAME.toClient.register(766, 767, 0x47, Respawn.class, Respawn::new);            // 1.20.5-1.21.1
        GAME.toClient.register(768, 769, 0x4C, Respawn.class, Respawn::new);            // 1.21.2-1.21.4
        GAME.toClient.register(770, 772, 0x4B, Respawn.class, Respawn::new);            // 1.21.5-1.21.8
        GAME.toClient.register(773, 774, 0x50, Respawn.class, Respawn::new);            // 1.21.9-1.21.11
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x52, Respawn.class, Respawn::new); // 26.1+

        // Switch-flow packets (mirrors BungeeCord's registrations).
        GAME.toClient.register(47, 106, 0x1A, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(107, 392, 0x1B, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(393, 476, 0x1C, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(477, 572, 0x1B, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(573, 734, 0x1C, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(735, 750, 0x1B, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(751, 754, 0x1A, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(755, 758, 0x1B, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(759, 759, 0x18, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(760, 760, 0x1A, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(761, 761, 0x19, EntityStatus.class, EntityStatus::new);
        GAME.toClient.register(762, 763, 0x1C, EntityStatus.class, EntityStatus::new);

        GAME.toClient.register(573, 734, 0x1F, GameState.class, GameState::new);
        GAME.toClient.register(735, 750, 0x1E, GameState.class, GameState::new);
        GAME.toClient.register(751, 754, 0x1D, GameState.class, GameState::new);
        GAME.toClient.register(755, 758, 0x1E, GameState.class, GameState::new);
        GAME.toClient.register(759, 759, 0x1B, GameState.class, GameState::new);
        GAME.toClient.register(760, 760, 0x1D, GameState.class, GameState::new);
        GAME.toClient.register(761, 761, 0x1C, GameState.class, GameState::new);
        GAME.toClient.register(762, 763, 0x1F, GameState.class, GameState::new);

        GAME.toClient.register(477, 572, 0x41, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(573, 734, 0x42, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(735, 750, 0x41, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(751, 754, 0x41, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(755, 758, 0x4A, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(759, 759, 0x49, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(760, 760, 0x4C, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(761, 761, 0x4B, ViewDistance.class, ViewDistance::new);
        GAME.toClient.register(762, 763, 0x4F, ViewDistance.class, ViewDistance::new);

        GAME.toClient.register(47, 106, 0x3B, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(107, 334, 0x3F, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(335, 337, 0x41, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(338, 392, 0x42, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(393, 476, 0x45, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(477, 572, 0x49, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(573, 754, 0x4A, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(755, 759, 0x53, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(760, 760, 0x56, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(761, 761, 0x54, ScoreboardObjective.class, ScoreboardObjective::new);
        GAME.toClient.register(762, 763, 0x58, ScoreboardObjective.class, ScoreboardObjective::new);
        // 1.20.2 keeps the JSON display name (1.20.3+ NBT components stay relayed raw).
        GAME.toClient.register(764, 764, 0x5A, ScoreboardObjective.class, ScoreboardObjective::new);

        GAME.toClient.register(47, 106, 0x3C, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(107, 334, 0x42, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(335, 337, 0x44, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(338, 392, 0x45, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(393, 476, 0x48, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(477, 572, 0x4C, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(573, 754, 0x4D, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(755, 759, 0x56, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(760, 760, 0x59, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(761, 761, 0x57, ScoreboardScore.class, ScoreboardScore::new);
        GAME.toClient.register(762, 763, 0x5B, ScoreboardScore.class, ScoreboardScore::new);
        // 1.20.2 keeps the action-byte format (1.20.3+ has no action byte and extra fields; raw).
        GAME.toClient.register(764, 764, 0x5D, ScoreboardScore.class, ScoreboardScore::new);

        GAME.toClient.register(47, 106, 0x3E, Team.class, Team::new);
        GAME.toClient.register(107, 334, 0x41, Team.class, Team::new);
        GAME.toClient.register(335, 337, 0x43, Team.class, Team::new);
        GAME.toClient.register(338, 392, 0x44, Team.class, Team::new);
        GAME.toClient.register(393, 476, 0x47, Team.class, Team::new);
        GAME.toClient.register(477, 572, 0x4B, Team.class, Team::new);
        GAME.toClient.register(573, 754, 0x4C, Team.class, Team::new);
        GAME.toClient.register(755, 759, 0x55, Team.class, Team::new);
        GAME.toClient.register(760, 760, 0x58, Team.class, Team::new);
        GAME.toClient.register(761, 761, 0x56, Team.class, Team::new);
        GAME.toClient.register(762, 763, 0x5A, Team.class, Team::new);
        // 1.20.2 keeps the JSON components (1.20.3+ NBT prefix/suffix stay relayed raw).
        GAME.toClient.register(764, 764, 0x5C, Team.class, Team::new);

        GAME.toClient.register(107, 572, 0x0C, BossBar.class, BossBar::new);
        GAME.toClient.register(573, 734, 0x0D, BossBar.class, BossBar::new);
        GAME.toClient.register(735, 750, 0x0C, BossBar.class, BossBar::new);
        GAME.toClient.register(751, 754, 0x0C, BossBar.class, BossBar::new);
        GAME.toClient.register(755, 758, 0x0D, BossBar.class, BossBar::new);
        GAME.toClient.register(759, 761, 0x0A, BossBar.class, BossBar::new);
        GAME.toClient.register(762, 763, 0x0B, BossBar.class, BossBar::new);
        // 1.20.2 keeps the JSON boss bar title (1.20.3+ NBT titles stay relayed raw).
        GAME.toClient.register(764, 764, 0x0A, BossBar.class, BossBar::new);

        // Respawn (1.16-1.20.1), sent by the proxy right after the JoinGame on a server switch
        // so the client rebuilds its world state (BungeeCord behaviour).
        GAME.toClient.register(735, 750, 0x3A, Respawn.class, Respawn::new);            // 1.16-1.16.1
        GAME.toClient.register(751, 754, 0x39, Respawn.class, Respawn::new);            // 1.16.2-1.16.5
        GAME.toClient.register(755, 758, 0x3D, Respawn.class, Respawn::new);            // 1.17-1.18.2
        GAME.toClient.register(759, 759, 0x3B, Respawn.class, Respawn::new);            // 1.19
        GAME.toClient.register(760, 760, 0x3E, Respawn.class, Respawn::new);            // 1.19.1-1.19.2
        GAME.toClient.register(761, 761, 0x3D, Respawn.class, Respawn::new);            // 1.19.3
        GAME.toClient.register(762, 763, 0x41, Respawn.class, Respawn::new);            // 1.19.4-1.20.1

        // PluginMessage (custom payload), registered for every supported version in both
        // directions so the proxy can intercept the BungeeCord channel, client/server brands and
        // the Forge FML handshake (BungeeCord TO_CLIENT / TO_SERVER PluginMessage mappings).
        // Clientbound:
        GAME.toClient.register(47, 106, 0x3F, PluginMessage.class, PluginMessage::new);       // 1.8-1.8.9
        GAME.toClient.register(107, 392, 0x18, PluginMessage.class, PluginMessage::new);      // 1.9-1.12.2
        GAME.toClient.register(393, 476, 0x19, PluginMessage.class, PluginMessage::new);      // 1.13-1.13.2
        GAME.toClient.register(477, 572, 0x18, PluginMessage.class, PluginMessage::new);      // 1.14-1.14.4
        GAME.toClient.register(573, 734, 0x19, PluginMessage.class, PluginMessage::new);      // 1.15-1.15.2
        GAME.toClient.register(735, 750, 0x18, PluginMessage.class, PluginMessage::new);      // 1.16-1.16.1
        GAME.toClient.register(751, 754, 0x17, PluginMessage.class, PluginMessage::new);      // 1.16.2-1.16.5
        GAME.toClient.register(755, 758, 0x18, PluginMessage.class, PluginMessage::new);      // 1.17-1.18.2
        GAME.toClient.register(759, 759, 0x15, PluginMessage.class, PluginMessage::new);      // 1.19
        GAME.toClient.register(760, 760, 0x16, PluginMessage.class, PluginMessage::new);      // 1.19.1-1.19.2
        GAME.toClient.register(761, 761, 0x15, PluginMessage.class, PluginMessage::new);      // 1.19.3
        GAME.toClient.register(762, 763, 0x17, PluginMessage.class, PluginMessage::new);      // 1.19.4-1.20.1
        GAME.toClient.register(764, 765, 0x18, PluginMessage.class, PluginMessage::new);      // 1.20.2-1.20.4
        GAME.toClient.register(766, 769, 0x19, PluginMessage.class, PluginMessage::new);      // 1.20.5-1.21.4
        GAME.toClient.register(770, Integer.MAX_VALUE, 0x18, PluginMessage.class, PluginMessage::new); // 1.21.5+

        // Serverbound:
        GAME.toServer.register(47, 106, 0x17, PluginMessage.class, PluginMessage::new);       // 1.8-1.8.9
        GAME.toServer.register(107, 334, 0x09, PluginMessage.class, PluginMessage::new);      // 1.9-1.11.2
        GAME.toServer.register(335, 337, 0x0A, PluginMessage.class, PluginMessage::new);      // 1.12
        GAME.toServer.register(338, 392, 0x09, PluginMessage.class, PluginMessage::new);      // 1.12.1-1.13.2
        GAME.toServer.register(393, 476, 0x0A, PluginMessage.class, PluginMessage::new);      // 1.13-1.13.2
        GAME.toServer.register(477, 754, 0x0B, PluginMessage.class, PluginMessage::new);      // 1.14-1.16.5
        GAME.toServer.register(755, 758, 0x0A, PluginMessage.class, PluginMessage::new);      // 1.17-1.18.2
        GAME.toServer.register(759, 759, 0x0C, PluginMessage.class, PluginMessage::new);      // 1.19
        GAME.toServer.register(760, 760, 0x0D, PluginMessage.class, PluginMessage::new);      // 1.19.1-1.19.2
        GAME.toServer.register(761, 761, 0x0C, PluginMessage.class, PluginMessage::new);      // 1.19.3
        GAME.toServer.register(762, 763, 0x0D, PluginMessage.class, PluginMessage::new);      // 1.19.4-1.20.1
        GAME.toServer.register(764, 764, 0x0F, PluginMessage.class, PluginMessage::new);      // 1.20.2
        GAME.toServer.register(765, 765, 0x10, PluginMessage.class, PluginMessage::new);      // 1.20.3-1.20.4
        GAME.toServer.register(766, 767, 0x12, PluginMessage.class, PluginMessage::new);      // 1.20.5-1.21.1
        GAME.toServer.register(768, 770, 0x14, PluginMessage.class, PluginMessage::new);      // 1.21.2-1.21.5
        GAME.toServer.register(771, 774, 0x15, PluginMessage.class, PluginMessage::new);      // 1.21.6-1.21.11
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x16, PluginMessage.class, PluginMessage::new); // 26.1+

        // StartConfiguration (1.20.2+): re-enters the configuration phase on a server switch.
        // Carrying it through the codecs switches the connection to CONFIGURATION (BungeeCord maps).
        GAME.toClient.register(764, 764, 0x65, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(765, 765, 0x67, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(766, 767, 0x69, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(768, 769, 0x70, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(770, 772, 0x6F, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(773, 774, 0x74, StartConfiguration.class, StartConfiguration::new);
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x76, StartConfiguration.class, StartConfiguration::new);

        GAME.toServer.register(764, 765, 0x0B, StartConfiguration.class, StartConfiguration::new);
        GAME.toServer.register(766, 767, 0x0C, StartConfiguration.class, StartConfiguration::new);
        GAME.toServer.register(768, 770, 0x0E, StartConfiguration.class, StartConfiguration::new);
        GAME.toServer.register(771, 774, 0x0F, StartConfiguration.class, StartConfiguration::new);
        GAME.toServer.register(775, Integer.MAX_VALUE, 0x10, StartConfiguration.class, StartConfiguration::new);

        GAME.toClient.register(47, 106, 0x47, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.8-1.8.9
        GAME.toClient.register(107, 109, 0x48, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.9-1.9.2
        GAME.toClient.register(110, 334, 0x47, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.9.4-1.11.2
        GAME.toClient.register(335, 337, 0x49, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.12
        GAME.toClient.register(338, 392, 0x4A, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.12.1-1.12.2
        GAME.toClient.register(393, 476, 0x4E, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.13-1.13.2
        GAME.toClient.register(477, 572, 0x53, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.14-1.14.4
        GAME.toClient.register(573, 734, 0x54, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.15-1.15.2
        GAME.toClient.register(735, 754, 0x53, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.16-1.16.5
        GAME.toClient.register(755, 756, 0x5E, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.17-1.17.1
        GAME.toClient.register(757, 758, 0x5F, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.18-1.18.2
        GAME.toClient.register(759, 759, 0x60, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.19
        GAME.toClient.register(760, 760, 0x63, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.19.1-1.19.2
        GAME.toClient.register(761, 761, 0x61, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.19.3
        GAME.toClient.register(762, 763, 0x65, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.19.4-1.20.1
        GAME.toClient.register(764, 764, 0x68, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.20.2
        GAME.toClient.register(765, 765, 0x6A, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.20.3-1.20.4 (NBT)
        GAME.toClient.register(766, 767, 0x6D, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.20.5-1.21.1
        GAME.toClient.register(768, 769, 0x74, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.21.2-1.21.4
        GAME.toClient.register(770, 773, 0x73, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.21.5-1.21.8
        GAME.toClient.register(774, 774, 0x78, TabListHeaderFooter.class, TabListHeaderFooter::new);   // 1.21.9-1.21.11
        GAME.toClient.register(775, Integer.MAX_VALUE, 0x7A, TabListHeaderFooter.class, TabListHeaderFooter::new); // 26.1+
    }

    public ProtocolData getData(Direction direction) {
        return direction == Direction.TO_SERVER ? toServer : toClient;
    }

    /**
     * Decodes a packet id into a fresh packet instance for the given protocol version, or returns
     * {@code null} when the id is not part of this state/direction/version (used by the decoder to
     * fall back to raw passthrough).
     */
    public DefinedPacket createPacket(Direction direction, int packetId, int protocolVersion) {
        return getData(direction).createPacket(packetId, protocolVersion);
    }

    public boolean hasPacket(Direction direction, int packetId, int protocolVersion) {
        return getData(direction).hasPacket(packetId, protocolVersion);
    }

    public int getId(Direction direction, Class<? extends DefinedPacket> packetClass, int protocolVersion) {
        return getData(direction).getId(packetClass, protocolVersion);
    }
}
