package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.dto.yggdrasil.CharacterInformationJson;
import moe.koseirin.nyanruaineo.dto.yggdrasil.Property;
import moe.koseirin.nyanruaineo.entity.Yggdrasil;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.ErrorUtils.ErrorResponse;
import moe.koseirin.nyanruaineo.utils.RedisUtils.RedisService;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.WebMvc.StrictIpResolver;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Yggdrasil sessionserver（join / hasJoined / profile）业务逻辑。
 */
@Service
public class YggdrasilSessionService {

    private final YggdrasilRepository yggdrasilRepository;
    private final UserDevicesRepository userDevicesRepository;
    private final utilset utilset;
    private final RedisService redisService;
    private final StrictIpResolver strictIpResolver;
    private final Respond respond;
    private final YggdrasilAuthService yggdrasilAuthService;
    private final YggdrasilTexturesBuilder texturesBuilder;

    @Value("${yggdrasil.privateKey}")
    private String privateKey;

    public YggdrasilSessionService(YggdrasilRepository yggdrasilRepository, UserDevicesRepository userDevicesRepository, utilset utilset, RedisService redisService, StrictIpResolver strictIpResolver, Respond respond, YggdrasilAuthService yggdrasilAuthService, YggdrasilTexturesBuilder texturesBuilder) {
        this.yggdrasilRepository = yggdrasilRepository;
        this.userDevicesRepository = userDevicesRepository;
        this.utilset = utilset;
        this.redisService = redisService;
        this.strictIpResolver = strictIpResolver;
        this.respond = respond;
        this.yggdrasilAuthService = yggdrasilAuthService;
        this.texturesBuilder = texturesBuilder;
    }

    public ResponseEntity<?> join(String data, HttpServletRequest request) {
        if (data == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(data));
        if (!json.containsKey("accessToken") || !json.containsKey("selectedProfile")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数accessToken或selectedProfile杂鱼喵~", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        if (!json.containsKey("serverId")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数serverId杂鱼喵~!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        String encryptedToken = json.getString("accessToken");
        String accessToken = utilset.decrypt(encryptedToken, privateKey);
        String selectedProfile = json.getString("selectedProfile");
        String serverId = json.getString("serverId");

        if (accessToken.isEmpty() || selectedProfile.isEmpty()) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数accessToken或selectedProfile杂鱼喵~", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        if (serverId.isEmpty()) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数serverId杂鱼喵~!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String uid = userDevicesRepository.findUidByToken(accessToken);
        if (uid == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("令牌无效杂鱼喵~!", "ForbiddenOperationException", "ForbiddenOperationException"));
        }

        String mcuuid = yggdrasilRepository.GetPlayerUUID(uid);
        if (mcuuid == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("令牌有效,但似乎您并没有Yggdrasil账户杂鱼喵~!", "ForbiddenOperationException", "ForbiddenOperationException"));
        }

        if (!selectedProfile.equals(mcuuid.replace("-", ""))) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("令牌有效,但似乎这个令牌并不属于这个MINECRAFT账户杂鱼喵~!", "ForbiddenOperationException", "ForbiddenOperationException"));
        }
        if (serverId.length() <= 8) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("令牌有效,账户核对正确,但似乎这个服务器的serverId并不符合此Yggdrasil服务器的要求杂鱼喵~!", "ForbiddenOperationException", "ForbiddenOperationException"));
        }

        JSONObject sessionData = new JSONObject();
        sessionData.put("reqIp", strictIpResolver.getStrictClientIp(request));
        sessionData.put("accessToken", accessToken);
        redisService.setValueWithExpiration(serverId, JSONObject.toJSONString(sessionData), 30, TimeUnit.SECONDS);
        return ResponseEntity.status(204).build();
    }

    public ResponseEntity<?> hasJoined(HttpServletRequest request) {
        String username = request.getParameter("username");
        String serverId = request.getParameter("serverId");

        if (username == null || serverId == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        // Shared internal (non-HTTP) verification: the proxy calls the same service directly.
        JSONObject profile = yggdrasilAuthService.hasJoined(username, serverId);
        if (profile == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("无效的会话杂鱼喵!", "ForbiddenOperationException", "ForbiddenOperationException"));
        }

        List<Property> properties = new ArrayList<>();
        JSONArray profileProperties = profile.getJSONArray("properties");
        if (profileProperties != null) {
            for (int i = 0; i < profileProperties.size(); i++) {
                JSONObject prop = profileProperties.getJSONObject(i);
                properties.add(new Property(prop.getString("name"), prop.getString("value"),
                        prop.containsKey("signature") ? prop.getString("signature") : null));
            }
        }
        return ResponseEntity.ok(new CharacterInformationJson(profile.getString("id"), profile.getString("name"), properties));
    }

    public ResponseEntity<?> profile(String uuid, HttpServletRequest request) {
        if (uuid == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String cleanUuid = uuid.replace("-", "");
        if (cleanUuid.length() != 32) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("非法UUID杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String fullUuid = java.util.UUID.fromString(
                cleanUuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5")
        ).toString();

        Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(fullUuid);
        if (yggdrasil == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 404, new ErrorResponse("玩家不存在杂鱼喵!", "Not Found", "Not Found"));
        }

        boolean unsigned = true;
        String unsignedParam = request.getParameter("unsigned");
        if (unsignedParam != null) {
            unsigned = Boolean.parseBoolean(unsignedParam);
        }

        List<Property> properties = new ArrayList<>();
        properties.add(texturesBuilder.buildTexturesProperty(yggdrasil.getUuid(), yggdrasil.getPlayername(), !unsigned, !unsigned));

        return ResponseEntity.ok(new CharacterInformationJson(yggdrasil.getUuid().replace("-", ""), yggdrasil.getPlayername(), properties));
    }
}
