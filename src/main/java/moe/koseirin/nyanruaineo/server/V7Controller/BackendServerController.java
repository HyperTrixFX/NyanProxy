package moe.koseirin.nyanruaineo.server.V7Controller;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.dto.BackendBanDTO;
import moe.koseirin.nyanruaineo.dto.BackendBroadcastDTO;
import moe.koseirin.nyanruaineo.dto.BackendUnbanDTO;
import moe.koseirin.nyanruaineo.dto.PlayerKickDTO;
import moe.koseirin.nyanruaineo.dto.PlayerTransferDTO;
import moe.koseirin.nyanruaineo.entity.ServerList;
import moe.koseirin.nyanruaineo.services.ServerListService;
import moe.koseirin.nyanruaineo.services.impl.BackendApiFuncImpl;
import moe.koseirin.nyanruaineo.utils.Respond;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * v7 后端服务器操作接口：子服务器插件通过配置的访问令牌（见 {@link ServerList}）
 * 连接到代理并委托处理封禁/解封/踢出/广播/转移/查询等操作。
 * <p>
 * 鉴权方式：请求头 {@code Authorization: Bearer <token>}，令牌在服务端只存哈希。
 */
@RestController
@RequestMapping("/api/v7/backend")
public class BackendServerController {

    private final ServerListService serverListService;
    private final BackendApiFuncImpl backendApiFunc;
    private final Respond respond;

    public BackendServerController(ServerListService serverListService,
                                   BackendApiFuncImpl backendApiFunc,
                                   Respond respond) {
        this.serverListService = serverListService;
        this.backendApiFunc = backendApiFunc;
        this.respond = respond;
    }

    @GetMapping("/servers")
    public ResponseEntity<?> servers(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(backendApiFunc.listServers());
    }

    @GetMapping("/players")
    public ResponseEntity<?> players(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return ResponseEntity.ok(backendApiFunc.listPlayers());
    }

    @PostMapping("/players/ban")
    public ResponseEntity<?> ban(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @RequestBody BackendBanDTO dto) {
        ServerList server = authenticate(authorization);
        if (server == null) {
            return unauthorized();
        }
        return backendApiFunc.ban(
                dto == null ? null : dto.getUuid(),
                dto == null ? null : dto.getReason(),
                dto == null ? null : dto.getType(),
                dto == null ? null : dto.getExpire(),
                "Neko-AntiCheat");
    }

    @PostMapping("/players/unban")
    public ResponseEntity<?> unban(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody BackendUnbanDTO dto) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return backendApiFunc.unban(dto == null ? null : dto.getUuid());
    }

    /** 连接认证：校验某 UUID 当前是否由本代理转发（后端插件在登录时调用）。 */
    @PostMapping("/players/verify")
    public ResponseEntity<?> verify(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody BackendUnbanDTO dto) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return backendApiFunc.verify(dto == null ? null : dto.getUuid());
    }

    @PostMapping("/players/kick")
    public ResponseEntity<?> kick(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody PlayerKickDTO dto) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return backendApiFunc.kick(dto == null ? null : dto.getPlayer(), dto == null ? null : dto.getReason());
    }

    @PostMapping("/players/transfer")
    public ResponseEntity<?> transfer(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody PlayerTransferDTO dto) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return backendApiFunc.transfer(
                dto == null ? null : dto.getPlayer(),
                dto == null ? null : dto.getTargetServer());
    }

    @PostMapping("/broadcast")
    public ResponseEntity<?> broadcast(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody BackendBroadcastDTO dto) {
        if (authenticate(authorization) == null) {
            return unauthorized();
        }
        return backendApiFunc.broadcast(dto == null ? null : dto.getMessage());
    }

    private ServerList authenticate(String authorization) {
        if (authorization == null) {
            return null;
        }
        String raw = authorization.replace("Bearer ", "").trim();
        if (raw.isEmpty() || "undefined".equals(raw)) {
            return null;
        }
        return serverListService.authenticate(raw);
    }

    private String serverName(ServerList server) {
        if (server.getServerName() != null && !server.getServerName().isBlank()) {
            return server.getServerName() + " (" + server.getUid() + ")";
        }
        return server.getUid();
    }

    private ResponseEntity<?> unauthorized() {
        return respond.respond(MediaType.APPLICATION_JSON, 401,
                "message", "Unauthorized: invalid or disabled server token",
                "timestamp", LocalDateTime.now());
    }
}
