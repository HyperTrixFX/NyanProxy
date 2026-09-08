package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.RedisUtils.RedisService;
import org.springframework.stereotype.Service;


@Slf4j
@Service
public class YggdrasilAuthService {

    private final YggdrasilRepository yggdrasilRepository;
    private final UserDevicesRepository userDevicesRepository;
    private final RedisService redisService;
    private final YggdrasilTexturesBuilder texturesBuilder;

    public YggdrasilAuthService(YggdrasilRepository yggdrasilRepository,
                                UserDevicesRepository userDevicesRepository,
                                RedisService redisService,
                                YggdrasilTexturesBuilder texturesBuilder) {
        this.yggdrasilRepository = yggdrasilRepository;
        this.userDevicesRepository = userDevicesRepository;
        this.redisService = redisService;
        this.texturesBuilder = texturesBuilder;
    }


    public JSONObject hasJoined(String username, String serverId) {
        if (username == null || serverId == null) {
            return null;
        }
        try {
            Object sessionObj = redisService.getValue(serverId);
            if (sessionObj == null) {
                return null;
            }
            redisService.deleteValue(serverId);
            JSONObject sessionData = JSONObject.parseObject(sessionObj.toString());
            if (sessionData == null) {
                return null;
            }

            String accessToken = sessionData.getString("accessToken");
            String nuid = userDevicesRepository.findUidByToken(accessToken);
            if (nuid == null) {
                return null;
            }

            String mcUuid = yggdrasilRepository.GetPlayerUUID(nuid);
            String mcName = yggdrasilRepository.GetPlayerNAME(nuid);
            if (mcUuid == null || !username.equals(mcName)) {
                return null;
            }

            JSONArray properties = new JSONArray();
            properties.add(texturesBuilder.buildTexturesPropertyJson(mcUuid, mcName, true, true));

            JSONObject profile = new JSONObject();
            profile.put("id", mcUuid.replace("-", ""));
            profile.put("name", mcName);
            profile.put("properties", properties);
            return profile;
        } catch (Exception e) {
            log.warn("Internal Yggdrasil hasJoined failed for {}: {}", username, e.getMessage());
            return null;
        }
    }
}
