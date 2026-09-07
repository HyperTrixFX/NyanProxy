package moe.koseirin.nyanruaineo.server.V3Contorller;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.dto.PlayerKickDTO;
import moe.koseirin.nyanruaineo.dto.PlayerTransferDTO;
import moe.koseirin.nyanruaineo.dto.ServerCredentialDTO;
import moe.koseirin.nyanruaineo.utils.System.PermissionNodes;
import moe.koseirin.nyanruaineo.services.PermissionService;
import moe.koseirin.nyanruaineo.services.ServerListService;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.services.impl.ProxyFuncImpl;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;


@RestController
@RequestMapping("/api/zako/v3/proxy")
public class ProxyController {

    private final ProxyFuncImpl proxyFunc;
    private final PermissionService permissionService;
    private final utilset utilset;
    private final UserDevicesRepository userDevicesRepository;
    private final BanUserRepository banUserRepository;
    private final ServerListService serverListService;
    private final Respond respond;

    @Value("${yggdrasil.privateKey}")
    private String privateKey;

    public ProxyController(ProxyFuncImpl proxyFunc, PermissionService permissionService, utilset utilset, UserDevicesRepository userDevicesRepository, BanUserRepository banUserRepository, ServerListService serverListService, Respond respond) {
        this.proxyFunc = proxyFunc;
        this.permissionService = permissionService;
        this.utilset = utilset;
        this.userDevicesRepository = userDevicesRepository;
        this.banUserRepository = banUserRepository;
        this.serverListService = serverListService;
        this.respond = respond;
    }


    // 后端服务器管理
    @GetMapping("/servers")
    public ResponseEntity<?> listServers(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return ResponseEntity.ok(proxyFunc.getAllServers());
    }

    @PostMapping("/servers")
    public ResponseEntity<?> createServer(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody BackendServer newServer) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.addServer(newServer);
    }

    @PutMapping("/servers/{uid}")
    public ResponseEntity<?> editServer(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String uid, @RequestBody BackendServer updatedServer) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.updateServer(uid, updatedServer);
    }

    @DeleteMapping("/servers/{uid}")
    public ResponseEntity<?> deleteServer(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String uid) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.removeServer(uid);
    }


    // 后端子服务器鉴权凭据管理（v7 接口使用；创建/重置时返回一次性 Token）
    @GetMapping("/serverlist")
    public ResponseEntity<?> listServerCredentials(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return serverListService.list();
    }

    @PostMapping("/serverlist")
    public ResponseEntity<?> createServerCredential(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody ServerCredentialDTO dto) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return serverListService.create(dto == null ? null : dto.getUid(), dto == null ? null : dto.getServerName(), resolveUid(authorization));
    }

    @PutMapping("/serverlist/{uid}")
    public ResponseEntity<?> updateServerCredential(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String uid, @RequestBody ServerCredentialDTO dto) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return serverListService.update(uid,
                dto == null ? null : dto.getServerName(),
                dto == null ? null : dto.getEnabled(),
                dto == null ? null : dto.getRegenerate(),
                resolveUid(authorization));
    }

    @DeleteMapping("/serverlist/{uid}")
    public ResponseEntity<?> deleteServerCredential(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String uid) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return serverListService.delete(uid);
    }


    // 代理配置管理
    @GetMapping("/config")
    public ResponseEntity<?> listConfigs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return ResponseEntity.ok(proxyFunc.getProxyConfigs());
    }

    @PutMapping("/config")
    public ResponseEntity<?> updateConfigs(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, String> updates) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.updateProxyConfigs(updates);
    }


    // 玩家管理（转移 / 踢出）
    @PostMapping("/players/transfer")
    public ResponseEntity<?> transferPlayer(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody PlayerTransferDTO dto) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.transferPlayer(dto == null ? null : dto.getPlayer(), dto == null ? null : dto.getTargetServer());
    }

    @PostMapping("/players/kick")
    public ResponseEntity<?> kickPlayer(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody PlayerKickDTO dto) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return proxyFunc.kickPlayer(dto == null ? null : dto.getPlayer(), dto == null ? null : dto.getReason());
    }


    // 权限节点管理
    @GetMapping("/permissions")
    public ResponseEntity<?> listPermissions(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam String uid) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        return ResponseEntity.ok(permissionService.getPermissions(uid));
    }

    @PostMapping("/permissions")
    public ResponseEntity<?> grantPermission(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestParam String uid, @RequestParam String node) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        permissionService.grant(uid, node);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/permissions")
    public ResponseEntity<?> revokePermission(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam String uid,
                                              @RequestParam String node) {
        if (!authorized(authorization)) {
            return forbidden();
        }
        permissionService.revoke(uid, node);
        return ResponseEntity.ok().build();
    }


    private String resolveUid(String authorization) {
        if (authorization == null) {
            return null;
        }
        String raw = authorization.replace("Bearer ", "").trim();
        if (raw.isEmpty() || "undefined".equals(raw)) {
            return null;
        }
        String token = utilset.decrypt(raw, privateKey);
        if (token == null) {
            return null;
        }
        return userDevicesRepository.findUidByToken(token);
    }

    private boolean authorized(String authorization) {
        String uid = resolveUid(authorization);
        return uid != null && !banUserRepository.existsByUidAndIsActiveTrue(uid)
                && permissionService.hasPermission(uid, PermissionNodes.PROXY_ADMIN);
    }

    private ResponseEntity<?> forbidden() {
        return respond.respond(MediaType.APPLICATION_JSON,403, "message","Permission denied!","timestamp", LocalDateTime.now());
    }
}
