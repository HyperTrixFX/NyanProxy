package moe.koseirin.nyanruaineo.services.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import moe.koseirin.nyanruaineo.Minecraft.service.BanKickService;
import moe.koseirin.nyanruaineo.dto.UserEditDTO;
import moe.koseirin.nyanruaineo.entity.Accounts;
import moe.koseirin.nyanruaineo.entity.BanUserList;
import moe.koseirin.nyanruaineo.entity.NyanIDuser;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.BanUserRepository;
import moe.koseirin.nyanruaineo.repository.NyanIDuserRepository;
import moe.koseirin.nyanruaineo.repository.UserDevicesRepository;
import moe.koseirin.nyanruaineo.repository.YggdrasilRepository;
import moe.koseirin.nyanruaineo.services.PermissionService;
import moe.koseirin.nyanruaineo.utils.Respond;
import moe.koseirin.nyanruaineo.utils.utilset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/*
 * @author KoseiRin_
 * awa
 */

/**
 * V3 用户管理业务逻辑（列表/详情/编辑/启停/封禁/解封）。权限节点鉴权由
 * {@code UserManagerController} 负责，权限授权/回收复用 {@link PermissionService}。
 */
@Service
public class UserManageFuncImpl {

    private final AccountsRepository accountsRepository;
    private final NyanIDuserRepository nyanIDuserRepository;
    private final BanUserRepository banUserRepository;
    private final UserDevicesRepository userDevicesRepository;
    private final YggdrasilRepository yggdrasilRepository;
    private final PermissionService permissionService;
    private final BanKickService banKickService;
    private final Respond respond;
    private final utilset utilset;

    public UserManageFuncImpl(AccountsRepository accountsRepository, NyanIDuserRepository nyanIDuserRepository, BanUserRepository banUserRepository, UserDevicesRepository userDevicesRepository, YggdrasilRepository yggdrasilRepository, PermissionService permissionService, BanKickService banKickService, Respond respond, utilset utilset) {
        this.accountsRepository = accountsRepository;
        this.nyanIDuserRepository = nyanIDuserRepository;
        this.banUserRepository = banUserRepository;
        this.userDevicesRepository = userDevicesRepository;
        this.yggdrasilRepository = yggdrasilRepository;
        this.permissionService = permissionService;
        this.banKickService = banKickService;
        this.respond = respond;
        this.utilset = utilset;
    }

    /** 分页列出/搜索用户（只返回安全字段，绝不暴露密码或密钥）。 */
    @Transactional(readOnly = true)
    public ResponseEntity<?> listUsers(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("username").ascending());
        Page<Accounts> result = (keyword == null || keyword.isBlank())
                ? accountsRepository.findAll(pageable)
                : accountsRepository.searchAll(keyword.trim(), pageable);

        JSONObject body = new JSONObject();
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        JSONArray items = new JSONArray();
        for (Accounts a : result.getContent()) {
            items.add(toSafeUserJson(a));
        }
        body.put("items", items);
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 用户详情：账号 + 资料 + Yggdrasil + 权限 + 封禁/设备统计。 */
    @Transactional(readOnly = true)
    public ResponseEntity<?> getUserDetail(String uid) {
        Accounts accounts = accountsRepository.GetUser(uid);
        if (accounts == null) {
            return notFound();
        }

        JSONObject body = toSafeUserJson(accounts);
        NyanIDuser profile = nyanIDuserRepository.getUser(uid);
        body.put("profile", profile == null ? null : toProfileJson(profile));
        body.put("bind", accounts.getBind());
        body.put("microsoft", accounts.getMicrosoftAccount() != null);
        body.put("yggdrasilUuid", yggdrasilRepository.GetPlayerUUID(uid));
        body.put("yggdrasilName", yggdrasilRepository.GetPlayerNAME(uid));
        body.put("permissions", permissionService.getPermissions(uid));
        body.put("banCount", banUserRepository.COUNTByUid(uid));
        body.put("deviceCount", userDevicesRepository.countByUid(uid));
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 编辑资料（昵称/简介/经验/开发者标志），只更新传入的非空字段。 */
    @Transactional
    public ResponseEntity<?> editUser(String uid, UserEditDTO dto) {
        NyanIDuser profile = nyanIDuserRepository.getUser(uid);
        if (profile == null) {
            return notFound();
        }
        if (dto.getNickname() != null && !dto.getNickname().isBlank()) {
            profile.setNickname(dto.getNickname());
        }
        if (dto.getDescription() != null) {
            profile.setDescription(dto.getDescription());
        }
        if (dto.getExp() != null) {
            profile.setExp(dto.getExp());
        }
        if (dto.getIsDeveloper() != null) {
            profile.setIsDeveloper(dto.getIsDeveloper());
        }
        nyanIDuserRepository.save(profile);
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", "User updated", "timestamp", LocalDateTime.now());
    }

    /** 启停账号。 */
    @Transactional
    public ResponseEntity<?> setActive(String uid, boolean active) {
        Accounts accounts = accountsRepository.GetUser(uid);
        if (accounts == null) {
            return notFound();
        }
        accountsRepository.UpdateActive(active, uid);
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", active ? "Account enabled" : "Account disabled", "timestamp", LocalDateTime.now());
    }

    /** 封禁账号（默认死封 type=6，永久）。 */
    @Transactional
    public ResponseEntity<?> banUser(String uid, String reason, Integer type, String expire, String operatorUid) {
        Accounts accounts = accountsRepository.GetUser(uid);
        if (accounts == null) {
            return notFound();
        }
        LocalDateTime expireTime = parseExpire(expire);
        if (expire != null && !expire.isBlank() && expireTime == null) {
            return respond.respond(MediaType.APPLICATION_JSON, 400, "message", "Invalid expire format, use ISO-8601 e.g. 2025-01-01T00:00:00", "timestamp", LocalDateTime.now());
        }

        BanUserList ban = new BanUserList();
        ban.setBanID(utilset.RandomString(13));
        ban.setUid(uid);
        ban.setTargetType(BanUserList.TARGET_UID);
        ban.setReason(reason == null || reason.isBlank() ? "Banned by an administrator" : reason);
        ban.setActive(true);
        ban.setType(type == null ? BanUserList.TYPE_DEAD_BAN : type);
        ban.setBanTime(LocalDateTime.now());
        ban.setBannedBy(operatorUid == null || operatorUid.isBlank() ? "Admin" : operatorUid);
        ban.setExpireTime(expireTime);
        BanUserList saved = banUserRepository.save(ban);
        int kicked = banKickService.kickOnlinePlayers(saved);
        return respond.respond(MediaType.APPLICATION_JSON, 200,
                "message", "Banned", "banId", saved.getBanID(), "kicked", kicked, "timestamp", LocalDateTime.now());
    }

    /** 解封账号的全部生效封禁。 */
    @Transactional
    public ResponseEntity<?> unbanUser(String uid) {
        int affected = banUserRepository.deactivateByUid(uid);
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", "Unbanned", "affected", affected, "timestamp", LocalDateTime.now());
    }

    /** 分页列出当前生效封禁的用户（含封禁详情）。 */
    @Transactional(readOnly = true)
    public ResponseEntity<?> listBannedUsers(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("BanTime").descending());
        Page<BanUserList> bans = banUserRepository.findActiveBans(pageable);
        JSONObject body = new JSONObject();
        body.put("total", bans.getTotalElements());
        body.put("page", bans.getNumber());
        body.put("size", bans.getSize());
        JSONArray items = new JSONArray();
        for (BanUserList b : bans.getContent()) {
            JSONObject o = new JSONObject();
            o.put("banId", b.getBanID());
            o.put("uid", b.getUid());
            o.put("targetType", b.getTargetType());
            o.put("reason", b.getReason());
            o.put("type", b.getType());
            o.put("banTime", b.getBanTime());
            o.put("expireTime", b.getExpireTime());
            o.put("bannedBy", b.getBannedBy());
            Accounts a = accountsRepository.GetUser(b.getUid());
            o.put("username", a == null ? null : a.getUsername());
            o.put("email", a == null ? null : a.getEmail());
            items.add(o);
        }
        body.put("items", items);
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 分页列出开发者用户。 */
    @Transactional(readOnly = true)
    public ResponseEntity<?> listDevelopers(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), Sort.by("uid").ascending());
        Page<NyanIDuser> devs = nyanIDuserRepository.findDevelopers(pageable);
        JSONObject body = new JSONObject();
        body.put("total", devs.getTotalElements());
        body.put("page", devs.getNumber());
        body.put("size", devs.getSize());
        JSONArray items = new JSONArray();
        for (NyanIDuser n : devs.getContent()) {
            JSONObject o = new JSONObject();
            o.put("uid", n.getUid());
            o.put("nickname", n.getNickname());
            Accounts a = accountsRepository.GetUser(n.getUid());
            o.put("username", a == null ? null : a.getUsername());
            o.put("email", a == null ? null : a.getEmail());
            items.add(o);
        }
        body.put("items", items);
        return respond.respond(MediaType.APPLICATION_JSON, 200, body);
    }

    /** 设置/取消开发者标志。 */
    @Transactional
    public ResponseEntity<?> setDeveloper(String uid, boolean active) {
        NyanIDuser profile = nyanIDuserRepository.getUser(uid);
        if (profile == null) {
            return notFound();
        }
        profile.setIsDeveloper(active);
        nyanIDuserRepository.save(profile);
        return respond.respond(MediaType.APPLICATION_JSON, 200, "message", active ? "Developer granted" : "Developer revoked", "timestamp", LocalDateTime.now());
    }

    private JSONObject toSafeUserJson(Accounts a) {
        JSONObject o = new JSONObject();
        o.put("uid", a.getUid());
        o.put("username", a.getUsername());
        o.put("email", a.getEmail());
        o.put("isActive", a.getIsActive());
        o.put("registerTime", a.getRegisterTime());
        return o;
    }

    private JSONObject toProfileJson(NyanIDuser p) {
        JSONObject o = new JSONObject();
        o.put("nickname", p.getNickname());
        o.put("description", p.getDescription());
        o.put("exp", p.getExp());
        o.put("isDeveloper", p.isIsDeveloper());
        return o;
    }

    private ResponseEntity<?> notFound() {
        return respond.respond(MediaType.APPLICATION_JSON, 404, "message", "Not Found Account", "timestamp", LocalDateTime.now());
    }

    private int clampSize(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private LocalDateTime parseExpire(String expire) {
        if (expire == null || expire.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(expire.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
