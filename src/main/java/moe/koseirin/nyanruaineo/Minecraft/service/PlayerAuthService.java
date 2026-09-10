package moe.koseirin.nyanruaineo.Minecraft.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.protocol.packet.LoginSuccess;
import moe.koseirin.nyanruaineo.Minecraft.util.LogThrottle;
import moe.koseirin.nyanruaineo.services.YggdrasilAuthService;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;


@Slf4j
@Service
public class PlayerAuthService {

    private static final String MOJANG_AUTH_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private final YggdrasilAuthService yggdrasilAuthService;

    public PlayerAuthService(YggdrasilAuthService yggdrasilAuthService) {
        this.yggdrasilAuthService = yggdrasilAuthService;
    }

    public CompletableFuture<PlayerProfile> authenticate(String username, byte[] sharedSecret, byte[] publicKey) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String serverId = generateServerHash(sharedSecret, publicKey);

                // 1) Yggdrasil sessionserver, verified.
                JSONObject externalProfile = yggdrasilAuthService.hasJoined(username, serverId);
                if (externalProfile != null) {
                    PlayerProfile profile = parseProfile(externalProfile);
                    // DEBUG：每次登录都打一条「认证成功」属于重复信息，玩家进服的 INFO 由
                    // InitialHandler 负责（这里两个分支以前各打一条 INFO）。
                    log.debug("Yggdrasil authentication successful for {} ({}) with {} properties",
                            profile.name(), profile.uuid(), profile.properties().size());
                    return profile;
                }

                // 2) Mojang session server fallback.
                PlayerProfile profile = authenticateWithMojang(username, serverId);
                if (profile != null) {
                    log.debug("Mojang authentication successful for {} ({}) with {} properties",
                            profile.name(), profile.uuid(), profile.properties().size());
                }
                return profile;
            } catch (Exception e) {
                // A failed session is a normal login rejection, not a server error: complete with
                // null so the login handler can answer the client with an "invalid session" kick.
                // 限流：账号扫描/集中重连时这里会刷屏。
                String gate = LogThrottle.acquire("auth-failed", 10_000L);
                if (gate != null) {
                    log.warn("Authentication failed for {}: {}{}", username, e.getMessage(), gate);
                }
                return null;
            }
        });
    }

    private PlayerProfile authenticateWithMojang(String username, String serverId) throws Exception {
        String url = MOJANG_AUTH_URL + "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&serverId=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        log.debug("Mojang auth URL: {}", url);

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                // Invalid / expired session (e.g. HTTP 403): a normal rejection, not a crash.
                log.warn("Mojang session rejected for {}: HTTP {}", username, response.code());
                return null;
            }
            JSONObject json = JSON.parseObject(response.body().string());
            if (json == null || !json.containsKey("id")) {
                log.debug("Mojang session returned no profile for {}", username);
                return null;
            }
            return parseProfile(json);
        }
    }

    /** Parses a Yggdrasil-protocol profile ({@code id}/{@code name}/{@code properties}) — the same
     * shape both the internal service and the Mojang session server return. */
    private static PlayerProfile parseProfile(JSONObject json) {
        String uuidStr = json.getString("id");
        String name = json.getString("name");

        List<LoginSuccess.Property> properties = new ArrayList<>();
        JSONArray propertiesArray = json.getJSONArray("properties");
        if (propertiesArray != null) {
            for (int i = 0; i < propertiesArray.size(); i++) {
                JSONObject prop = propertiesArray.getJSONObject(i);
                String propName = prop.getString("name");
                String propValue = prop.getString("value");
                String signature = prop.containsKey("signature") ? prop.getString("signature") : null;
                properties.add(new LoginSuccess.Property(propName, propValue, signature));
            }
        }

        UUID uuid = UUID.fromString(uuidStr.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"));
        return new PlayerProfile(name, uuid, properties);
    }

    private String generateServerHash(byte[] sharedSecret, byte[] publicKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(sharedSecret);
        digest.update(publicKey);
        byte[] hash = digest.digest();
        return new BigInteger(hash).toString(16);
    }

    public record PlayerProfile(String name, UUID uuid, List<LoginSuccess.Property> properties) {
    }
}
