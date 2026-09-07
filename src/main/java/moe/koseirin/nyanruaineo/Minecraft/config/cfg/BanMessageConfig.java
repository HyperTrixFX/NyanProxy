package moe.koseirin.nyanruaineo.Minecraft.config.cfg;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** 封禁屏幕模板配置，存储在 {@code proxy.ban-message} 下（与普通踢出模板分离）。 */
@Data
public class BanMessageConfig {
    private boolean enabled;
    /**
     * 封禁屏幕模板：{@code &} 颜色代码、{@code n}/{@code |} 换行符，
     * 占位符 {@code $playerName} / {@code $reason} / {@code $banId} / {@code $expireTime}
     * （永久封禁时 {@code $expireTime} 显示「永久」）。
     */
    @JSONField(name = "banned_message_base")
    private String bannedMessageBase;
}
