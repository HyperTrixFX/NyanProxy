package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.dto.yggdrasil.YggdrasilServerJsonLinks;
import moe.koseirin.nyanruaineo.dto.yggdrasil.YggdrasilServerJsonMeta;
import moe.koseirin.nyanruaineo.dto.yggdrasil.YggdrasilServerJsonRoot;
import moe.koseirin.nyanruaineo.entity.Accounts;
import moe.koseirin.nyanruaineo.entity.Yggdrasil;
import moe.koseirin.nyanruaineo.entity.YggdrasilPlayer;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.System.EnumList.UUIDtype;
import moe.koseirin.nyanruaineo.utils.SqlService.YggdrasilPlayerService;
import moe.koseirin.nyanruaineo.utils.SqlService.YggdrasilService;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Yggdrasil 服务根元数据与账号开通业务逻辑
 */
@Service
public class YggdrasilServerService {

    @Value("${yggdrasil.serverName}")
    private String serverName;

    @Value("${yggdrasil.implementationName}")
    private String implementationName;

    @Value("${yggdrasil.implementationVersion}")
    private String implementationVersion;

    @Value("${yggdrasil.links-homepage}")
    private String links_homepage;

    @Value("${yggdrasil.links-register}")
    private String links_register;

    @Value("${yggdrasil.feature-non_email_login}")
    private boolean feature_non_email_login;

    @Value("${yggdrasil.SkinDomains}")
    private String SkinDomains;

    @Value("${yggdrasil.publicKey}")
    private String publicKey;

    @Value("${yggdrasil.privateKey}")
    private String privateKey;

    private final UserDevicesRepository userDevicesRepository;
    private final YggdrasilRepository yggdrasilRepository;
    private final AccountsRepository accountsRepository;
    private final YggdrasilService yggdrasilService;
    private final YggdrasilPlayerService yggdrasilPlayerService;
    private final utilset utilset;
    private final Respond respond;

    public YggdrasilServerService(UserDevicesRepository userDevicesRepository, YggdrasilRepository yggdrasilRepository, AccountsRepository accountsRepository, YggdrasilService yggdrasilService, YggdrasilPlayerService yggdrasilPlayerService, utilset utilset, Respond respond) {
        this.userDevicesRepository = userDevicesRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.accountsRepository = accountsRepository;
        this.yggdrasilService = yggdrasilService;
        this.yggdrasilPlayerService = yggdrasilPlayerService;
        this.utilset = utilset;
        this.respond = respond;
    }

    /**  authlib-injector 元数据。 */
    public YggdrasilServerJsonRoot rootMeta() {
        YggdrasilServerJsonLinks links = new YggdrasilServerJsonLinks();
        links.setHomepage(links_homepage);
        links.setRegister(links_register);

        YggdrasilServerJsonMeta meta = new YggdrasilServerJsonMeta();
        meta.setImplementationName(implementationName);
        meta.setImplementationVersion(implementationVersion);
        meta.setServerName(serverName);
        meta.setLinks(links);
        meta.setFeature_non_email_login(feature_non_email_login);

        YggdrasilServerJsonRoot root = new YggdrasilServerJsonRoot();
        root.setMeta(meta);
        root.setSkinDomains(new String[]{SkinDomains});
        root.setSignaturePublickey(publicKey);
        return root;
    }

    public ResponseEntity<?> openAccount(String authorization) {
        if (authorization == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 404, "message", "RequestBody is NULL MiaoWu~", "timestamp", LocalDateTime.now());
        }
        String rawToken = authorization.replace("Bearer ", "").replace(" ", "");
        String token = utilset.decrypt(rawToken, privateKey);
        String uid = userDevicesRepository.findUidByToken(token);
        String uuid = yggdrasilRepository.GetPlayerUUID(uid);
        Accounts accounts = accountsRepository.GetUser(uid);
        if (uuid == null) {
            String generatedUuid = utilset.GenerateUUID(UUIDtype.Yggdrasil, false, uid);
            Yggdrasil yggdrasil = new Yggdrasil();
            yggdrasil.setUseSkin(false);
            yggdrasil.setUseCAPE(false);
            yggdrasil.setPlayername(accounts.getUsername());
            yggdrasil.setNyanuid(uid);
            yggdrasil.setUuid(generatedUuid);
            yggdrasil.setType(1);
            yggdrasilService.save(yggdrasil);
            YggdrasilPlayer yggdrasilPlayer = new YggdrasilPlayer();
            yggdrasilPlayer.setUuid(generatedUuid);
            yggdrasilPlayer.setSkinTexturesType(1);
            yggdrasilPlayer.setSkinTexturesHash(null);
            yggdrasilPlayer.setCAPETexturesHash(null);
            yggdrasilPlayerService.save(yggdrasilPlayer);
            return ResponseEntity.status(204).build();
        } else {
            return respond.respond(MediaType.APPLICATION_JSON, 404, "message", "RequestBody is NULL MiaoWu~", "timestamp", LocalDateTime.now());
        }
    }
}
