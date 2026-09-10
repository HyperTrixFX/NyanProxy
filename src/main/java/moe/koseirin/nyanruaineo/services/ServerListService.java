package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONObject;
import moe.koseirin.nyanruaineo.Minecraft.config.cfg.BackendServer;
import moe.koseirin.nyanruaineo.Minecraft.service.BackendServerManager;
import moe.koseirin.nyanruaineo.entity.ServerList;
import moe.koseirin.nyanruaineo.repository.ServerListRepository;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 后端子服务器鉴权凭据（{@link ServerList}）的增删改查与 v7 令牌校验。
 * <p>
 * 服务端只保存访问令牌的 SHA-256 哈希，原始令牌仅在创建/重置时返回一次；
 * v7 接口每次请求通过 {@link #authenticate(String)} 校验 {@code Authorization: Bearer <token>}。
 */
@Service
public class ServerListService {

    private static final String TOKEN_PREFIX = "ny_";
    private static final int TOKEN_RANDOM_LENGTH = 48;

    private final ServerListRepository serverListRepository;
    private final BackendServerManager backendServerManager;
    private final utilset utilset;
    private final Respond respond;

    public ServerListService(ServerListRepository serverListRepository,
                             BackendServerManager backendServerManager,
                             utilset utilset,
                             Respond respond) {
        this.serverListRepository = serverListRepository;
        this.backendServerManager = backendServerManager;
        this.utilset = utilset;
        this.respond = respond;
    }

    /** 生成一个新的随机访问令牌（只返回一次，服务端只存其哈希）。 */
    public String generateToken() {
        return TOKEN_PREFIX + utilset.RandomString(TOKEN_RANDOM_LENGTH);
    }

    /** 令牌的 SHA-256 hex 哈希，用于存储与查询。 */
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 校验 v7 请求令牌，返回对应且启用的 {@link ServerList}，否则返回 {@code null}。
     * 校验成功时刷新 {@code lastUsedAt}。
     */
    @Transactional
    public ServerList authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        ServerList server = serverListRepository.findByTokenHash(hashToken(token.trim())).orElse(null);
        if (server == null || !server.isEnabled()) {
            return null;
        }
        server.setLastUsedAt(LocalDateTime.now());
        serverListRepository.save(server);
        return server;
    }

    /** 列出所有鉴权记录（不含令牌哈希）。 */
    public ResponseEntity<?> list() {
        List<ServerList> all = serverListRepository.findAll();
        com.alibaba.fastjson2.JSONArray items = new com.alibaba.fastjson2.JSONArray();
        for (ServerList s : all) {
            items.add(toSafeJson(s));
        }
        return respond.respond(MediaType.APPLICATION_JSON, 200, "items", items, "count", items.size());
    }

    /** 为某个已配置的后端服务器创建鉴权凭据，返回一次性令牌。 */
    @Transactional
    public ResponseEntity<?> create(String uid, String serverName, String operatorUid) {
        if (uid == null || uid.isBlank()) {
            return badRequest("uid is required");
        }
        BackendServer backend = findBackendByUid(uid.trim());
        if (backend == null) {
            return badRequest("Unknown backend server uid: " + uid);
        }
        if (serverListRepository.existsById(uid.trim())) {
            return conflict("Credential already exists for server: " + uid);
        }

        String token = generateToken();
        ServerList record = new ServerList();
        record.setUid(uid.trim());
        record.setServerName(resolveServerName(serverName, backend));
        record.setTokenHash(hashToken(token));
        record.setEnabled(true);
        record.setCreatedAt(LocalDateTime.now());
        record.setCreatedBy(normalizeOperator(operatorUid));
        serverListRepository.save(record);

        JSONObject body = toSafeJson(record);
        body.put("token", token);
        body.put("note", "token 只显示这一次，请立即保存");
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 更新鉴权记录（改名/启停/重置令牌）；重置时返回新的一次性令牌。 */
    @Transactional
    public ResponseEntity<?> update(String uid, String serverName, Boolean enabled, Boolean regenerate, String operatorUid) {
        ServerList record = serverListRepository.findById(uid).orElse(null);
        if (record == null) {
            return notFound("Credential not found for server: " + uid);
        }
        if (serverName != null && !serverName.isBlank()) {
            record.setServerName(serverName.trim());
        }
        if (enabled != null) {
            record.setEnabled(enabled);
        }
        String newToken = null;
        if (Boolean.TRUE.equals(regenerate)) {
            newToken = generateToken();
            record.setTokenHash(hashToken(newToken));
            record.setCreatedBy(normalizeOperator(operatorUid));
        }
        serverListRepository.save(record);

        JSONObject body = toSafeJson(record);
        if (newToken != null) {
            body.put("token", newToken);
            body.put("note", "token 只显示这一次，请立即保存");
        }
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 删除鉴权记录。 */
    @Transactional
    public ResponseEntity<?> delete(String uid) {
        if (!serverListRepository.existsById(uid)) {
            return notFound("Credential not found for server: " + uid);
        }
        serverListRepository.deleteById(uid);
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "message", "Deleted credential for server: " + uid,
                "timestamp", LocalDateTime.now());
    }

    /** 安全字段（绝不暴露 tokenHash）。 */
    private JSONObject toSafeJson(ServerList s) {
        JSONObject o = new JSONObject();
        o.put("uid", s.getUid());
        o.put("serverName", s.getServerName());
        o.put("enabled", s.isEnabled());
        o.put("createdAt", s.getCreatedAt());
        o.put("lastUsedAt", s.getLastUsedAt());
        o.put("createdBy", s.getCreatedBy());
        return o;
    }

    private BackendServer findBackendByUid(String uid) {
        List<BackendServer> servers = backendServerManager.listServers();
        if (servers == null) {
            return null;
        }
        for (BackendServer s : servers) {
            if (s != null && uid.equals(s.getUid())) {
                return s;
            }
        }
        return null;
    }

    private String resolveServerName(String serverName, BackendServer backend) {
        if (serverName != null && !serverName.isBlank()) {
            return serverName.trim();
        }
        return backend.getName() == null || backend.getName().isBlank() ? backend.getUid() : backend.getName();
    }

    private String normalizeOperator(String operatorUid) {
        return operatorUid == null || operatorUid.isBlank() ? "Admin" : operatorUid;
    }

    private ResponseEntity<?> badRequest(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 400, "message", message, "timestamp", LocalDateTime.now());
    }

    private ResponseEntity<?> notFound(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 404, "message", message, "timestamp", LocalDateTime.now());
    }

    private ResponseEntity<?> conflict(String message) {
        return respond.respond(MediaType.APPLICATION_JSON, 409, "message", message, "timestamp", LocalDateTime.now());
    }
}
