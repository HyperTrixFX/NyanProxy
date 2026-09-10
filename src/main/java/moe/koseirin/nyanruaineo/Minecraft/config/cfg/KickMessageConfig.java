package moe.koseirin.nyanruaineo.Minecraft.config.cfg;

/*
 * @author KoseiRin_
 * awa
 */

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/** 普通踢出屏幕模板配置，存储在 {@code proxy.kick-message} 下（与封禁模板分离）。 */
@Data
public class KickMessageConfig {
    private boolean enabled;
    /**
     * 普通踢出屏幕模板：{@code &} 颜色代码、{@code n}/{@code |} 换行符，
     * 占位符 {@code $playerName} / {@code $reason} / {@code $kickId}。
     */
    @JSONField(name = "kick_message_base")
    private String kickMessageBase;
}
