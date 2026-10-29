package moe.koseirin.nyanruaineo.services;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import moe.koseirin.nyanruaineo.entity.Accounts;
import moe.koseirin.nyanruaineo.entity.NyanIDuser;
import moe.koseirin.nyanruaineo.entity.QqBinding;
import moe.koseirin.nyanruaineo.repository.AccountsRepository;
import moe.koseirin.nyanruaineo.repository.NyanIDuserRepository;
import moe.koseirin.nyanruaineo.repository.QqBindingRepository;
import moe.koseirin.nyanruaineo.utils.EmailHelper.EmailService;
import moe.koseirin.nyanruaineo.utils.System.PermissionNodes;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * QQ union_openid 与 NyanID 账号的一对一绑定。
 * <p>
 * 绑定流程刻意只放在私聊：
 * <ol>
 *     <li>{@code /bind 邮箱}：向该邮箱发送 6 位验证码（5 分钟有效）；</li>
 *     <li>{@code /bind 验证码}：校验通过后写入 {@code qq_binding}。</li>
 * </ol>
 * 验证码只在 Redis 中保存 SHA-256，不保存明文；同一 QQ 60 秒内只能重发一次，最多尝试 5 次。
 */
@Slf4j
@Service
public class QqBindingService {

    private static final String CODE_KEY_PREFIX = "romyu:qq:bind:code:";
    private static final String COOLDOWN_KEY_PREFIX = "romyu:qq:bind:cooldown:";
    private static final String EMAIL_COOLDOWN_KEY_PREFIX = "romyu:qq:bind:email:";
    private static final String FAIL_KEY_PREFIX = "romyu:qq:bind:fail:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;

    private final QqBindingRepository bindingRepository;
    private final AccountsRepository accountsRepository;
    private final NyanIDuserRepository nyanIDuserRepository;
    private final EmailService emailService;
    private final StringRedisTemplate redisTemplate;
    private final PermissionService permissionService;
    private final SecureRandom secureRandom = new SecureRandom();

    public QqBindingService(QqBindingRepository bindingRepository,
                            AccountsRepository accountsRepository,
                            NyanIDuserRepository nyanIDuserRepository,
                            EmailService emailService,
                            StringRedisTemplate redisTemplate,
                            PermissionService permissionService) {
        this.bindingRepository = bindingRepository;
        this.accountsRepository = accountsRepository;
        this.nyanIDuserRepository = nyanIDuserRepository;
        this.emailService = emailService;
        this.redisTemplate = redisTemplate;
        this.permissionService = permissionService;
    }

    public record BindRequestResult(boolean success, String message) {
    }

    public record BindVerifyResult(boolean success, String message, String uid, String username) {
    }

    public Optional<QqBinding> getBinding(String unionOpenid) {
        if (unionOpenid == null || unionOpenid.isBlank()) {
            return Optional.empty();
        }
        try {
            return bindingRepository.findById(unionOpenid);
        } catch (Exception e) {
            log.warn("Failed to read QQ binding for {}", unionOpenid, e);
            return Optional.empty();
        }
    }

    public String getUid(String unionOpenid) {
        return getBinding(unionOpenid).map(QqBinding::getUid).orElse(null);
    }

    /** 绑定后的优先称呼：NyanID 昵称 > 用户名 > 空字符串。 */
    public String getDisplayName(String unionOpenid) {
        try {
            String uid = getUid(unionOpenid);
            if (uid == null) {
                return "";
            }
            NyanIDuser user = nyanIDuserRepository.getUser(uid);
            if (user != null && user.getNickname() != null && !user.getNickname().isBlank()) {
                return sanitizeDisplayName(user.getNickname());
            }
            Accounts account = accountsRepository.GetUser(uid);
            if (account != null && account.getUsername() != null && !account.getUsername().isBlank()) {
                return sanitizeDisplayName(account.getUsername());
            }
            return "";
        } catch (Exception e) {
            log.warn("Failed to resolve display name for {}", unionOpenid, e);
            return "";
        }
    }

    /** 广播权限：* / minecraftproxy.admin / minecraftproxy.command.broadcast / nyanid.admin.cctop 任一即可。 */
    public boolean hasBroadcastPermission(String unionOpenid) {
        String uid = getUid(unionOpenid);
        if (uid == null) {
            return false;
        }
        return permissionService.hasPermission(uid, PermissionNodes.ROOT)
                || permissionService.hasPermission(uid, PermissionNodes.PROXY_ADMIN)
                || permissionService.hasPermission(uid, PermissionNodes.COMMAND_BROADCAST)
                || permissionService.hasPermission(uid, PermissionNodes.NYANID_ADMIN);
    }

    /**
     * 第一步：发送绑定验证码。
     * <p>
     * 邮箱不存在时也返回统一文案，避免通过机器人枚举 NyanID 注册邮箱。
     */
    public BindRequestResult requestBindCode(String unionOpenid, String rawEmail) {
        if (unionOpenid == null || unionOpenid.isBlank()) {
            return new BindRequestResult(false, "咱暂时认不出你的 QQ 身份喵，稍后再试一次吧。");
        }
        if (bindingRepository.existsById(unionOpenid)) {
            return new BindRequestResult(false, "这个 QQ 已经绑定过 NyanID 账号了喵。想换绑的话先发送 /unbind 解绑。");
        }
        String email = rawEmail == null ? "" : rawEmail.trim();
        if (!email.matches("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,12}")) {
            return new BindRequestResult(false, "邮箱格式好像不太对喵，请发送 /bind 你的邮箱。");
        }
        String cooldownKey = COOLDOWN_KEY_PREFIX + unionOpenid;
        Long ttl = redisTemplate.getExpire(cooldownKey, TimeUnit.SECONDS);
        if (ttl != null && ttl > 0) {
            return new BindRequestResult(false, "验证码刚发过喵，等 " + ttl + " 秒再试一次吧。");
        }
        String emailCooldownKey = EMAIL_COOLDOWN_KEY_PREFIX
                + sha256(email.toLowerCase(Locale.ROOT));
        if (Boolean.TRUE.equals(redisTemplate.hasKey(emailCooldownKey))) {
            return new BindRequestResult(false, "这个邮箱刚收过验证码喵，等 60 秒再试一次吧。");
        }

        String uid = accountsRepository.findByEmail(email);
        if (uid == null) {
            uid = accountsRepository.findByEmail(email.toLowerCase(Locale.ROOT));
        }
        if (uid == null) {
            // 防账号枚举：设置冷却并返回统一成功文案，不真正发信。
            redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_TTL);
            redisTemplate.opsForValue().set(emailCooldownKey, "1", COOLDOWN_TTL);
            return new BindRequestResult(true, "如果这个邮箱注册过 NyanID，验证码已经发出去啦。5 分钟内发送 /bind 验证码 完成绑定喵。");
        }
        if (bindingRepository.existsByUid(uid)) {
            return new BindRequestResult(false, "这个 NyanID 账号已经绑定过其他 QQ 了喵。请先在原 QQ 上解绑，或联系管理员。");
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        JSONObject payload = new JSONObject();
        payload.put("uid", uid);
        payload.put("email", email);
        payload.put("codeHash", sha256(code));
        payload.put("createdAt", System.currentTimeMillis());
        String codeKey = CODE_KEY_PREFIX + unionOpenid;
        redisTemplate.opsForValue().set(codeKey, payload.toJSONString(), CODE_TTL);
        // 新验证码重新计算尝试次数，避免上一次的失败次数把新验证码直接锁死。
        redisTemplate.delete(FAIL_KEY_PREFIX + unionOpenid);

        if (!emailService.sendQqBindCode(email, code)) {
            redisTemplate.delete(codeKey);
            redisTemplate.delete(cooldownKey);
            redisTemplate.delete(emailCooldownKey);
            return new BindRequestResult(false, "验证码邮件发送失败了喵，请稍后再试。");
        }
        redisTemplate.opsForValue().set(cooldownKey, "1", COOLDOWN_TTL);
        redisTemplate.opsForValue().set(emailCooldownKey, "1", COOLDOWN_TTL);
        log.info("Sent QQ binding code to {} for union_openid {}", maskEmail(email), unionOpenid);
        return new BindRequestResult(true,
                "验证码已经发到 " + maskEmail(email) + " 啦，5 分钟内有效喵。\n发送 /bind 验证码 完成绑定。");
    }

    /**
     * 第二步：校验验证码并写入绑定。
     */
    @Transactional
    public BindVerifyResult verifyBindCode(String unionOpenid, String rawCode) {
        if (unionOpenid == null || unionOpenid.isBlank()) {
            return new BindVerifyResult(false, "咱暂时认不出你的 QQ 身份喵，稍后再试一次吧。", null, null);
        }
        if (bindingRepository.existsById(unionOpenid)) {
            return new BindVerifyResult(false, "这个 QQ 已经绑定过 NyanID 账号了喵。想换绑的话先发送 /unbind 解绑。", null, null);
        }
        String code = rawCode == null ? "" : rawCode.trim();
        if (!code.matches("\\d{6}")) {
            return new BindVerifyResult(false, "验证码应该是 6 位数字喵。先发送 /bind 邮箱 获取验证码。", null, null);
        }
        String codeKey = CODE_KEY_PREFIX + unionOpenid;
        String failKey = FAIL_KEY_PREFIX + unionOpenid;
        String failValue = redisTemplate.opsForValue().get(failKey);
        int failed = failValue == null ? 0 : parseIntSafe(failValue);
        if (failed >= MAX_ATTEMPTS) {
            redisTemplate.delete(codeKey);
            return new BindVerifyResult(false, "验证码尝试次数太多喵，请重新发送 /bind 邮箱 获取新的验证码。", null, null);
        }

        String payloadJson = redisTemplate.opsForValue().get(codeKey);
        if (payloadJson == null) {
            return new BindVerifyResult(false, "验证码不存在或已经过期喵，请重新发送 /bind 邮箱 获取。", null, null);
        }
        JSONObject payload;
        try {
            payload = JSONObject.parseObject(payloadJson);
        } catch (Exception e) {
            redisTemplate.delete(codeKey);
            return new BindVerifyResult(false, "验证码状态异常喵，请重新发送 /bind 邮箱 获取。", null, null);
        }
        String expectedHash = payload.getString("codeHash");
        String actualHash = sha256(code);
        if (expectedHash == null || !MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.UTF_8),
                actualHash.getBytes(StandardCharsets.UTF_8))) {
            Long attempts = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, CODE_TTL);
            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(codeKey);
                return new BindVerifyResult(false, "验证码错误次数太多喵，请重新发送 /bind 邮箱 获取新的验证码。", null, null);
            }
            long left = MAX_ATTEMPTS - (attempts == null ? 1 : attempts);
            return new BindVerifyResult(false, "验证码不对喵，还可以再试 " + Math.max(0, left) + " 次。", null, null);
        }

        String uid = payload.getString("uid");
        if (uid == null || uid.isBlank()) {
            redisTemplate.delete(codeKey);
            return new BindVerifyResult(false, "验证码状态异常喵，请重新发送 /bind 邮箱 获取。", null, null);
        }
        // 验证期间可能被其他请求抢先绑定，写入前再检查一次。
        if (bindingRepository.existsById(unionOpenid)) {
            return new BindVerifyResult(false, "这个 QQ 刚刚已经绑定过 NyanID 账号了喵。", null, null);
        }
        if (bindingRepository.existsByUid(uid)) {
            return new BindVerifyResult(false, "这个 NyanID 账号刚刚已经绑定过其他 QQ 了喵。", null, null);
        }

        QqBinding binding = new QqBinding();
        binding.setUnionOpenid(unionOpenid);
        binding.setUid(uid);
        bindingRepository.save(binding);
        redisTemplate.delete(codeKey);
        redisTemplate.delete(failKey);

        Accounts account = accountsRepository.GetUser(uid);
        String username = account == null || account.getUsername() == null ? uid : account.getUsername();
        log.info("QQ union_openid {} bound to NyanID uid {}", unionOpenid, uid);
        return new BindVerifyResult(true,
                "绑定成功喵～以后咱就能认出你了，请多指教呀。", uid, username);
    }

    /** 解绑当前 QQ。返回是否确实删除了绑定。 */
    @Transactional
    public boolean unbind(String unionOpenid) {
        if (unionOpenid == null || unionOpenid.isBlank()) {
            return false;
        }
        int deleted = bindingRepository.deleteBinding(unionOpenid);
        if (deleted > 0) {
            try {
                redisTemplate.delete(CODE_KEY_PREFIX + unionOpenid);
                redisTemplate.delete(FAIL_KEY_PREFIX + unionOpenid);
                redisTemplate.delete(COOLDOWN_KEY_PREFIX + unionOpenid);
            } catch (Exception e) {
                log.warn("Failed to clean Redis binding state for {}", unionOpenid, e);
            }
            log.info("QQ union_openid {} unbound", unionOpenid);
        }
        return deleted > 0;
    }

    private int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        String name = email.substring(0, at);
        String visible = name.length() >= 3 ? name.substring(0, 2) : name.substring(0, 1);
        return visible + "***" + email.substring(at);
    }

    /** 昵称/用户名来自用户资料，作为称呼使用前先去掉控制字符并限制长度。 */
    private String sanitizeDisplayName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() > 32) {
            int end = 32;
            if (Character.isHighSurrogate(cleaned.charAt(end - 1))) {
                end--;
            }
            cleaned = cleaned.substring(0, end) + "…";
        }
        return cleaned;
    }
}
