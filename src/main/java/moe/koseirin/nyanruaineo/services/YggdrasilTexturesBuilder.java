package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.dto.yggdrasil.Property;
import moe.koseirin.nyanruaineo.dto.yggdrasil.TexturesJson;
import moe.koseirin.nyanruaineo.repository.YggdrasilPlayerRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Yggdrasil 纹理 JSON 超级拼装器。
 */
@Slf4j
@Component
public class YggdrasilTexturesBuilder {

    private final YggdrasilRepository yggdrasilRepository;
    private final YggdrasilPlayerRepository yggdrasilPlayerRepository;
    private final utilset utilset;

    @Value("${yggdrasil.APILocation}")
    private String APILocation;

    @Value("${yggdrasil.privateKey}")
    private String privateKey;

    public YggdrasilTexturesBuilder(YggdrasilRepository yggdrasilRepository,
                                    YggdrasilPlayerRepository yggdrasilPlayerRepository,
                                    utilset utilset) {
        this.yggdrasilRepository = yggdrasilRepository;
        this.yggdrasilPlayerRepository = yggdrasilPlayerRepository;
        this.utilset = utilset;
    }

    /**
     * 构建 textures JSON（含 timestamp/profileId/profileName/textures）。
     *
     * @param uuid              玩家 Minecraft UUID（带连字符）
     * @param profileName       玩家名
     * @param signatureRequired 是否写入 signatureRequired 字段；{@code null} 表示不写该字段
     */
    public JSONObject buildTexturesJson(String uuid, String profileName, Boolean signatureRequired) {
        JSONObject texturesJson = new JSONObject();
        texturesJson.put("timestamp", System.currentTimeMillis());
        texturesJson.put("profileId", uuid.replace("-", ""));
        texturesJson.put("profileName", profileName);
        if (signatureRequired != null) {
            texturesJson.put("signatureRequired", signatureRequired);
        }

        JSONObject textures = new JSONObject();
        texturesJson.put("textures", textures);

        String model = (yggdrasilPlayerRepository.getSkinTexturesType(uuid) == 1) ? "default" : "slim";
        if (Boolean.TRUE.equals(yggdrasilRepository.getUseSkin(uuid))) {
            TexturesJson.SkinTexture skin = new TexturesJson.SkinTexture(
                    APILocation + "/api/zako/res/textures/" + yggdrasilPlayerRepository.getSkinTexturesHash(uuid),
                    new TexturesJson.TextureMetadata(model));
            textures.put("SKIN", skin);
        }
        if (Boolean.TRUE.equals(yggdrasilRepository.getUseCAPE(uuid))) {
            TexturesJson.SkinTexture cape = new TexturesJson.SkinTexture(
                    APILocation + "/api/zako/res/textures/" + yggdrasilPlayerRepository.getCAPETexturesHash(uuid),
                    null);
            textures.put("CAPE", cape);
        }
        return texturesJson;
    }

    /**
     * 构建 {@code textures} 属性（{@link Property} 形式）：{@code value = base64(texturesJson)}，
     * {@code signature} 按需 RSA 签名（{@code sign=false} 时为 null）。
     */
    public Property buildTexturesProperty(String uuid, String profileName, Boolean signatureRequired, boolean sign) {
        JSONObject texturesJson = buildTexturesJson(uuid, profileName, signatureRequired);
        String value = Base64.getEncoder().encodeToString(texturesJson.toString().getBytes());
        return new Property("textures", value, sign ? signTextures(texturesJson) : null);
    }

    /**
     * 构建 {@code textures} 属性（{@link JSONObject} 形式），供需要直接返回 JSON 的
     * 内部会话校验（{@link YggdrasilAuthService#hasJoined}）使用。
     */
    public JSONObject buildTexturesPropertyJson(String uuid, String profileName, Boolean signatureRequired, boolean sign) {
        JSONObject texturesJson = buildTexturesJson(uuid, profileName, signatureRequired);
        JSONObject property = new JSONObject();
        property.put("name", "textures");
        property.put("value", Base64.getEncoder().encodeToString(texturesJson.toString().getBytes()));
        if (sign) {
            property.put("signature", signTextures(texturesJson));
        }
        return property;
    }

    private String signTextures(JSONObject texturesJson) {
        try {
            return utilset.sign(Base64.getEncoder().encode(texturesJson.toString().getBytes()), privateKey);
        } catch (Exception e) {
            log.warn("Failed to sign Yggdrasil textures: {}", e.getMessage());
            return null;
        }
    }
}
