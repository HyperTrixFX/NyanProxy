package moe.koseirin.nyanruaineo.dto;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** v7 后端委托封禁的请求体：按 Minecraft UUID 定位玩家。 */
@Getter
@Setter
public class BackendBanDTO {
    /** 玩家 Minecraft UUID（可带或不带连字符）。 */
    private String uuid;
    /** 封禁原因（可选）。 */
    private String reason;
    /** 封禁类型（可选，缺省 20=游戏封禁；支持 6=死封）。 */
    private Integer type;
    /** 解封时间（可选，ISO-8601，如 2025-01-01T00:00:00；缺省永久）。 */
    private String expire;
}
