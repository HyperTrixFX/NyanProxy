package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.dto.yggdrasil.CharacterInformationJson;
import moe.koseirin.nyanruaineo.dto.yggdrasil.Property;
import moe.koseirin.nyanruaineo.entity.UserDevices;
import moe.koseirin.nyanruaineo.entity.Yggdrasil;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.ErrorUtils.ErrorResponse;
import moe.koseirin.nyanruaineo.utils.PasswordHasher;
import moe.koseirin.nyanruaineo.utils.RedisUtils.RedisService;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.SqlService.UserDevicesService;
import moe.koseirin.nyanruaineo.utils.WebMvc.StrictIpResolver;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Yggdrasil authserver（authenticate / refresh / validate）业务逻辑。
 */
@Service
public class YggdrasilAuthenticateService {

    private final AccountsRepository accountsRepository;
    private final UserDevicesService userDevicesService;
    private final UserDevicesRepository userDevicesRepository;
    private final YggdrasilRepository yggdrasilRepository;
    private final BanUserRepository banUserRepository;
    private final RedisService redisService;
    private final utilset utilset;
    private final StrictIpResolver strictIpResolver;
    private final Respond respond;
    private final PasswordHasher passwordHasher;
    private final YggdrasilTexturesBuilder texturesBuilder;

    @Value("${yggdrasil.privateKey}")
    private String privateKey;
    @Value("${yggdrasil.publicKey}")
    private String publicKey;

    private final ConcurrentHashMap<String, Const> constMap = new ConcurrentHashMap<>();
    private final String EventID = "LoEvent1";

    public YggdrasilAuthenticateService(AccountsRepository accountsRepository, UserDevicesService userDevicesService, UserDevicesRepository userDevicesRepository, YggdrasilRepository yggdrasilRepository, BanUserRepository banUserRepository, RedisService redisService, utilset utilset, StrictIpResolver strictIpResolver, Respond respond, PasswordHasher passwordHasher, YggdrasilTexturesBuilder texturesBuilder) {
        this.accountsRepository = accountsRepository;
        this.userDevicesService = userDevicesService;
        this.userDevicesRepository = userDevicesRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.banUserRepository = banUserRepository;
        this.redisService = redisService;
        this.utilset = utilset;
        this.strictIpResolver = strictIpResolver;
        this.respond = respond;
        this.passwordHasher = passwordHasher;
        this.texturesBuilder = texturesBuilder;
    }

    public ResponseEntity<?> authenticate(String data, HttpServletRequest request) {
        if (data == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(data));
        if (!json.containsKey("username") || !json.containsKey("password")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数username或password杂鱼喵~", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        if (!json.containsKey("requestUser")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("The requestUser or clientToken is incorrect 杂鱼喵~", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        if (!json.containsKey("agent") || !json.getString("username").matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数agent杂鱼喵~", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String email = json.getString("username");
        String password = json.getString("password");
        String clientToken = json.getString("clientToken");
        String IP = strictIpResolver.getStrictClientIp(request);
        String ClientToken;
        Boolean requestUser = json.getBoolean("requestUser");
        JSONObject agent = json.getJSONObject("agent");
        String name = "McDef", version = "0.1";
        if (agent.containsKey("name") && agent.containsKey("version")) {
            name = agent.getString("name");
            version = agent.getString("version");
        }

        JSONObject BanEvent = new JSONObject();
        BanEvent.put(EventID, email);
        if (accountsRepository.findByEmail(email) == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("The account doesn't exist or is locked because of a password error 杂鱼喵~", "ForbiddenOperationException", "Invalid credentials. Invalid username or password."));
        }

        if (redisService.getValue(String.valueOf(BanEvent)) != null && redisService.getValue(String.valueOf(BanEvent)).equals(IP)) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("The account doesn't exist or is locked because of a password error 杂鱼喵~", "ForbiddenOperationException", "Invalid credentials. Invalid username or password."));
        }

        if (constMap.get(email) == null) {
            constMap.put(email, new Const(1));
        } else if (constMap.get(email).requestCount > 3) {
            constMap.remove(email);
            redisService.setValueWithExpiration(String.valueOf(BanEvent), IP, 180, TimeUnit.SECONDS);
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("The account doesn't exist or is locked because of a password error 杂鱼喵~", "ForbiddenOperationException", "Invalid credentials. Invalid username or password."));
        }

        String pwd = accountsRepository.LoginByEmail(email);
        if (!passwordHasher.matches(password, pwd)) {
            if (constMap.get(email) != null) {
                constMap.get(email).requestCount++;
            }
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("The account doesn't exist or is locked because of a password error 杂鱼喵~", "ForbiddenOperationException", "Invalid credentials. Invalid username or password."));
        }

        String uid = accountsRepository.findByEmail(email);
        // 旧 HMAC 散列透明迁移为 PBKDF2
        if (passwordHasher.isLegacy(pwd)) {
            accountsRepository.UpdatePassword(uid, passwordHasher.hash(password));
        }
        String MCUUID = yggdrasilRepository.GetPlayerUUID(uid);
        if (MCUUID == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 404, new ErrorResponse("The Yggdrasil account doesn't exist . 杂鱼喵~ ", "Not Found ", "Not Found Yggdrasil account "));
        }

        String MCNAME = yggdrasilRepository.GetPlayerNAME(MCUUID);
        String session = request.getSession().getId();
        List<String> banIds = banUserRepository.findBanIDByUid(uid, LocalDateTime.now());
        if (!banIds.isEmpty()) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("此用户已被封禁,封禁码:[" + banIds.getFirst() + "]杂鱼喵~", "ForbiddenOperationException", "ForbiddenOperationException"));
        }

        if (constMap.get(email) != null) {
            constMap.remove(email);
        }

        // 返回登录信息
        if (json.containsKey("clientToken")) {
            if (clientToken == null || clientToken.isEmpty()) {
                ClientToken = utilset.RandomString(32);
                String accessToken = utilset.RandomString(32);
                UserDevices userDevices = new UserDevices();
                userDevices.setUid(uid);
                userDevices.setDeviceID(name + ".Td" + version + "-Lo.-" + MCUUID);
                userDevices.setDeviceName("Minecraft");
                userDevices.setToken(accessToken);
                userDevices.setIp(IP);
                userDevices.setIsActive(true);
                userDevices.setSession(session);
                userDevices.setClientId(ClientToken);
                userDevices.setCreateTime(LocalDateTime.now());
                userDevicesService.save(userDevices);
                return respond.respond(MediaType.APPLICATION_JSON, 200, buildAuthenticateResponse(MCUUID, MCNAME, utilset.encrypt(accessToken, publicKey), ClientToken, requestUser, uid));
            } else {
                if (clientToken.length() == 32) {
                    UserDevices existing = userDevicesRepository.getByINFO(clientToken);
                    if (existing == null || existing.getUid() == null || !existing.getUid().equals(uid)) {
                        String issuedClientToken = (existing == null) ? clientToken : utilset.RandomString(32);
                        String accessToken = utilset.RandomString(32);
                        UserDevices uD = new UserDevices();
                        uD.setUid(uid);
                        uD.setDeviceID("Mc.Td-LoToken.-" + MCUUID);
                        uD.setDeviceName("Minecraft");
                        uD.setToken(accessToken);
                        uD.setIp(IP);
                        uD.setIsActive(true);
                        uD.setSession(session);
                        uD.setClientId(issuedClientToken);
                        uD.setCreateTime(LocalDateTime.now());
                        userDevicesService.save(uD);
                        return respond.respond(MediaType.APPLICATION_JSON, 200, (buildAuthenticateResponse(MCUUID, MCNAME, utilset.encrypt(accessToken, publicKey), issuedClientToken, requestUser, uid)));
                    } else {
                        return respond.respond(MediaType.APPLICATION_JSON, 200, (buildAuthenticateResponse(MCUUID, MCNAME, utilset.encrypt(existing.getToken(), publicKey), clientToken, requestUser, uid)));
                    }
                } else {
                    return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("非法clientToken长度,请尝试更换兼容启动器登录杂鱼喵!", "ForbiddenOperationException", "Invalid clientToken."));
                }
            }
        } else {
            ClientToken = utilset.RandomString(32);
            String accessToken = utilset.RandomString(32);
            UserDevices userDevices = new UserDevices();
            userDevices.setUid(uid);
            userDevices.setDeviceID("Mc.Td-LoToken.-" + MCUUID);
            userDevices.setDeviceName("Minecraft");
            userDevices.setToken(accessToken);
            userDevices.setIp(IP);
            userDevices.setIsActive(true);
            userDevices.setSession(session);
            userDevices.setClientId(ClientToken);
            userDevices.setCreateTime(LocalDateTime.now());
            userDevicesService.save(userDevices);
            return respond.respond(MediaType.APPLICATION_JSON, 200, (buildAuthenticateResponse(MCUUID, MCNAME, utilset.encrypt(accessToken, publicKey), ClientToken, requestUser, uid)));
        }
    }

    public ResponseEntity<?> refresh(String data) {
        if (data == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(data));
        if (!json.containsKey("accessToken")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数accessToken杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        if (!json.containsKey("requestUser")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数requestUser杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String decryptedToken = utilset.decrypt(json.getString("accessToken"), privateKey);
        boolean IsSelectedProfile = json.containsKey("selectedProfile");

        if (json.containsKey("clientToken")) {
            String reqClientToken = json.getString("clientToken");
            if (reqClientToken == null || reqClientToken.isEmpty()) {
                // 未指定 clientToken
                String existingClient = userDevicesRepository.findClientIdByToken(decryptedToken);
                if (existingClient == null) {
                    return respond.respond(MediaType.APPLICATION_JSON, 403,
                            new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid token."));
                }
                userDevicesRepository.UpdateCreateTime(LocalDateTime.now(), decryptedToken);
                String nyanid = userDevicesRepository.findUidByToken(decryptedToken);
                String newAccessToken = utilset.RandomString(32);
                userDevicesRepository.UpdateAccessToken(decryptedToken, newAccessToken);
                Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(nyanid);
                return respond.respond(MediaType.APPLICATION_JSON, 200, (buildRefreshResponse(IsSelectedProfile, json.getBoolean("requestUser"), utilset.encrypt(newAccessToken, publicKey), existingClient, yggdrasil.getPlayername(), yggdrasil.getUuid(), nyanid)));
            } else {
                // 指定 clientToken
                String existingClient = userDevicesRepository.findClientIdByToken(decryptedToken);
                if (existingClient == null) {
                    return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid token."));
                }
                if (!existingClient.equals(reqClientToken)) {
                    return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid clientToken."));
                }
                userDevicesRepository.UpdateCreateTime(LocalDateTime.now(), decryptedToken);
                String nyanid = userDevicesRepository.findUidByToken(decryptedToken);
                String newAccessToken = utilset.RandomString(32);
                userDevicesRepository.UpdateAccessToken(decryptedToken, newAccessToken);
                Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(nyanid);
                return respond.respond(MediaType.APPLICATION_JSON, 200, (buildRefreshResponse(IsSelectedProfile, json.getBoolean("requestUser"), utilset.encrypt(newAccessToken, publicKey), reqClientToken, yggdrasil.getPlayername(), yggdrasil.getUuid(), nyanid)));
            }
        } else {
            // 未指定 clientToken
            String existingClient = userDevicesRepository.findClientIdByToken(decryptedToken);
            if (existingClient == null) {
                return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid token."));
            }
            userDevicesRepository.UpdateCreateTime(LocalDateTime.now(), decryptedToken);
            String nyanid = userDevicesRepository.findUidByToken(decryptedToken);
            String newAccessToken = utilset.RandomString(32);
            userDevicesRepository.UpdateAccessToken(decryptedToken, newAccessToken);
            Yggdrasil yggdrasil = yggdrasilRepository.YggdrasilPlayer(nyanid);
            return respond.respond(MediaType.APPLICATION_JSON, 200, (buildRefreshResponse(IsSelectedProfile, json.getBoolean("requestUser"), utilset.encrypt(newAccessToken, publicKey), existingClient, yggdrasil.getPlayername(), yggdrasil.getUuid(), nyanid)));
        }
    }

    public ResponseEntity<?> validate(String data) {
        if (data == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的内容为NULL杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }
        JSONObject json = JSONObject.parseObject(JSONObject.toJSONString(data));
        if (!json.containsKey("accessToken")) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("你请求的json中缺少重要参数accessToken杂鱼喵!", "The parameter is incorrect", "The parameter is incorrect 杂鱼喵~"));
        }

        String decryptedToken = utilset.decrypt(json.getString("accessToken"), privateKey);

        if (json.containsKey("clientToken")) {
            String reqClientToken = json.getString("clientToken");
            if (reqClientToken == null || reqClientToken.isEmpty()) {
                if (userDevicesRepository.findClientIdByToken(decryptedToken) != null && userDevicesRepository.getActive(decryptedToken)) {
                    return ResponseEntity.status(204).build();
                } else {
                    return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid token."));
                }
            } else {
                String existingClient = userDevicesRepository.findClientIdByToken(decryptedToken);
                if (existingClient != null && existingClient.equals(reqClientToken) && userDevicesRepository.getActive(decryptedToken)) {
                    return ResponseEntity.status(204).build();
                } else {
                    return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid clientToken."));
                }
            }
        } else {
            if (userDevicesRepository.findClientIdByToken(decryptedToken) != null && userDevicesRepository.getActive(decryptedToken)) {
                return ResponseEntity.status(204).build();
            } else {
                return respond.respond(MediaType.APPLICATION_JSON, 403, new ErrorResponse("登录信息已过期杂鱼喵!", "ForbiddenOperationException", "Invalid token."));
            }
        }
    }

    private JSONObject buildAuthenticateResponse(String MCUUID, String MCNAME, String accessToken, String ClientToken, Boolean requestUser, String nyanid) {
        List<Property> properties = new ArrayList<>();
        properties.add(texturesBuilder.buildTexturesProperty(MCUUID, MCNAME, null, false));
        CharacterInformationJson characterInformationJson = new CharacterInformationJson(MCUUID.replace("-", ""), yggdrasilRepository.GetPlayerNAME(MCUUID), properties);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("accessToken", accessToken);
        jsonObject.put("clientToken", ClientToken);
        jsonObject.putArray("availableProfiles").add(characterInformationJson);
        jsonObject.put("selectedProfile", characterInformationJson);
        if (requestUser) {
            JSONObject Properties = new JSONObject();
            Properties.put("name", "preferredLanguage");
            Properties.put("value", "zh_CN");
            JSONObject user = new JSONObject();
            user.put("id", nyanid);
            user.putArray("properties").add(Properties);
            jsonObject.put("user", user);
        }
        return jsonObject;
    }

    private JSONObject buildRefreshResponse(boolean IsSelectedProfile, boolean requestUser, String accessToken, String clientToken, String name, String uuid, String nyanid) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("accessToken", accessToken);
        jsonObject.put("clientToken", clientToken);
        if (IsSelectedProfile) {
            List<Property> properties = new ArrayList<>();
            properties.add(texturesBuilder.buildTexturesProperty(uuid, name, null, false));
            CharacterInformationJson characterInformationJson = new CharacterInformationJson(uuid.replace("-", ""), name, properties);
            jsonObject.put("selectedProfile", characterInformationJson);
        }
        if (requestUser) {
            JSONObject Properties = new JSONObject();
            Properties.put("name", "preferredLanguage");
            Properties.put("value", "zh_CN");
            JSONObject user = new JSONObject();
            user.put("id", nyanid);
            user.putArray("properties").add(Properties);
            jsonObject.put("user", user);
        }
        return jsonObject;
    }

    private static class Const {
        int requestCount;

        Const(int requestCount) {
            this.requestCount = requestCount;
        }
    }
}
