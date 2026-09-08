package moe.koseirin.nyanruaineo.services.impl;

import jakarta.servlet.http.HttpServletRequest;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.Minecraft.MinecraftProxy;
import moe.koseirin.nyanruaineo.Minecraft.connection.UserConnection;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerMessageService;
import moe.koseirin.nyanruaineo.Minecraft.service.PlayerQueryService;
import moe.koseirin.nyanruaineo.NyanIdApplication;
import moe.koseirin.nyanruaineo.entity.Accounts;
import moe.koseirin.nyanruaineo.entity.NyanIDuser;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.NyanIDuserRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.utils.EmailHelper.EmailService;
import moe.koseirin.nyanruaineo.utils.RedisUtils.RedisService;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.WebMvc.StrictIpResolver;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

/*
 * @author KoseiRin_
 * awa
 */

@Slf4j
@Component
public class UserDataImpl {

    @Value("${yggdrasil.privateKey}")
    private String  privateKey;
    private final NyanIDuserRepository nyanIDuserRepository;
    private final UserDevicesRepository userDevicesRepository;
    private final YggdrasilRepository yggdrasilRepository;
    private final AccountsRepository accountsRepository;
    private final BanUserRepository banUserRepository;
    private final EmailService emailService;
    private final RedisService redisService;
    private final StrictIpResolver strictIpResolver;
    private final utilset utilset;
    private final Respond respond;
    private final PlayerQueryService playerQueryService;
    private final PlayerMessageService playerMessageService;



    public UserDataImpl(NyanIDuserRepository nyanIDuserRepository, UserDevicesRepository userDevicesRepository, YggdrasilRepository yggdrasilRepository, AccountsRepository accountsRepository, BanUserRepository banUserRepository, EmailService emailService, RedisService redisService, StrictIpResolver strictIpResolver, utilset utilset, Respond respond, MinecraftProxy minecraftProxy, PlayerQueryService playerQueryService, PlayerMessageService playerMessageService) {
        this.nyanIDuserRepository = nyanIDuserRepository;
        this.userDevicesRepository = userDevicesRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.accountsRepository = accountsRepository;
        this.banUserRepository = banUserRepository;
        this.emailService = emailService;
        this.redisService = redisService;
        this.strictIpResolver = strictIpResolver;
        this.utilset = utilset;
        this.respond = respond;
        this.playerQueryService = playerQueryService;
        this.playerMessageService = playerMessageService;
    }

    // 处理昵称更新
    public ResponseEntity<?> handleUpdateNickname(String nickname, NyanIDuser user, String uid) {

        // 验证昵称格式
        if (nickname == null || nickname.length() <= 3) {
            return respond.respond(MediaType.APPLICATION_JSON,403, "message","Illegal Request","timestamp", LocalDateTime.now());
        }

        // 检查敏感词
        if (NyanIdApplication.wordBs.contains(nickname)) {
            return respond.respond(MediaType.APPLICATION_JSON,401, "message","昵称含有非法内容,此请求已被服务器主动放弃喵~","timestamp", LocalDateTime.now());
        }

        // 检查昵称是否与当前相同
        if (Objects.equals(user.getNickname(), nickname)) {
            return respond.respond(MediaType.APPLICATION_JSON,400, "message","IllegalArgumentException","timestamp", LocalDateTime.now());
        }

        // 更新昵称
        nyanIDuserRepository.UpdateNickname(nickname, uid);
        return respond.respond(MediaType.APPLICATION_JSON,200, "message","Setting nickname success MiaoWu~","timestamp", LocalDateTime.now());
    }

    // 处理用户名更新
    public ResponseEntity<?> handleUpdateUsername(String username, Accounts account, HttpServletRequest request) {


        // 验证用户名格式
        if (username == null || username.length() <= 3 || !username.matches("(?=.*[a-zA-Z])[a-zA-Z0-9_]{3,20}")) {
            return respond.respond(MediaType.APPLICATION_JSON,400, "message","Username is invalid MiaoWu~","timestamp", LocalDateTime.now());
        }

        // 检查敏感词
        if (NyanIdApplication.wordBs.contains(username)) {
            return respond.respond(MediaType.APPLICATION_JSON,401, "message","用户名含有非法内容,此请求已被服务器主动放弃喵~","timestamp", LocalDateTime.now());
        }

        // 检查用户名是否已存在
        Accounts existingAccount = accountsRepository.GetUser(username);
        if (existingAccount != null) {
            return respond.respond(MediaType.APPLICATION_JSON,400, "message","Username already exists MiaoWu~","timestamp", LocalDateTime.now());

        }

        // 如果用户在yggdrasil有记录，更新玩家名称
        if (yggdrasilRepository.GetPlayerNAME(account.getUid()) != null) {
            yggdrasilRepository.UpdatePlayerName(username, account.getUid());
        }

        // 更新用户名
        accountsRepository.UpdateUsername(username, account.getUid());

        // 发送通知邮件
        String email = accountsRepository.GetEmailByUid(account.getUid());
        String clientIp = strictIpResolver.getStrictClientIp(request);
        emailService.NotificationEmail(email, clientIp, "Change username", account.getUid());

        // 登出设备
        userDevicesRepository.LogOut(account.getUid(), "Minecraft");
        return respond.respond(MediaType.APPLICATION_JSON,200, "message","Setting username success MiaoWu~","timestamp", LocalDateTime.now());
    }

    // 处理简介更新
    public ResponseEntity<?> handleUpdateDescription(String description, String userId) {

        // 验证简介格式
        if (description == null || description.length() <= 2 || description.length() >= 100) {
            return respond.respond(MediaType.APPLICATION_JSON,403, "message","Description is invalid MiaoWu~","timestamp", LocalDateTime.now());
        }

        // 检查敏感词
        if (NyanIdApplication.wordBs.contains(description)) {
            return respond.respond(MediaType.APPLICATION_JSON,401, "message","简介含有非法内容,此请求已被服务器主动放弃喵~","timestamp", LocalDateTime.now());
        }

        // 更新简介
        nyanIDuserRepository.SetDescriptionByUid(description, userId);
        return respond.respond(MediaType.APPLICATION_JSON,200, "message","Setting description success MiaoWu~","timestamp", LocalDateTime.now());
    }

    // 处理Minecraft绑定
    public ResponseEntity<?> handleBindMinecraft(String bindCode, Accounts account) {
        //验证绑定码
        if (bindCode == null || bindCode.isEmpty()) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, "message", "Code is invalid MiaoWu~", "timestamp", LocalDateTime.now());
        }

        Object uuidObject = redisService.getValue(bindCode);
        if (uuidObject == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 404, "message", "无效的绑定码杂鱼喵~", "timestamp", LocalDateTime.now());
        }

        String uuid = uuidObject.toString();
        if (accountsRepository.GetUser(uuid.replace("-","")) != null){
            return respond.respond(MediaType.APPLICATION_JSON, 401, "message", "该Minecraft账号已被绑定喵～", "timestamp", LocalDateTime.now());
        }
        if (accountsRepository.GetBindByUid(account.getUid()) != null){
            return respond.respond(MediaType.APPLICATION_JSON, 401, "message", "您已绑定过账号喵～", "timestamp", LocalDateTime.now());
        }
        //绑定Minecraft账号
        accountsRepository.BindMinecraftAccount(uuid.replace("-",""), account.getUid());
        // 同步该 UUID 下的正版（TARGET_UUID）封禁记录到 NyanID 账户，使申诉/历史/活跃异常可见
//        int migratedBans =
        banUserRepository.migrateUuidBansToUid(uuid.replace("-", ""), account.getUid());
//        if (migratedBans > 0) {
//            log.info("Migrated {} ban(s) from Minecraft UUID {} to NyanID uid {}", migratedBans, uuid, account.getUid());
//        }
        redisService.deleteValue(bindCode);
        redisService.deleteValue(uuidObject +"BindAccount");
        UserConnection playerInfo =  playerQueryService.getUserConnectionByUUID(uuid);
        if (playerInfo != null){
            playerMessageService.sendMessage(playerInfo.getServer().getUser(), "§5§l小鳥遊ホシノ §b§l»§l§6绑定成功喵～您的NyanID为:"+account.getUid());
            return respond.respond(MediaType.APPLICATION_JSON, 200, "message", "绑定成功喵~, uuid: " + playerInfo.getUuid(), "timestamp", LocalDateTime.now());
        }
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", "绑定成功喵~, uuid: " + uuid, "timestamp", LocalDateTime.now());
    }

    // 处理头像模式切换
    public ResponseEntity<?> handleToggleAvatarMode(String userId) {
        Boolean hasGifAvatar = nyanIDuserRepository.IsGIFAvatar(userId);

        if (hasGifAvatar == null || !hasGifAvatar) {
            return respond.respond(MediaType.APPLICATION_JSON, 403, "message", "NULL", "timestamp", LocalDateTime.now());
        }

        Boolean isGifEnabled = nyanIDuserRepository.EnableGIFAvatar(userId);

        // 切换GIF头像状态
        if (isGifEnabled != null && isGifEnabled) {
            nyanIDuserRepository.UpdateEnableGIFAvatar(false, userId);
        } else {
            nyanIDuserRepository.UpdateEnableGIFAvatar(true, userId);
        }
        return ResponseEntity.noContent().build();
    }









}
