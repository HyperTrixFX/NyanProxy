package moe.koseirin.nyanruaineo.dto;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** v7 后端委托解封的请求体：按 Minecraft UUID 定位玩家。 */
@Getter
@Setter
public class BackendUnbanDTO {
    /** 玩家 Minecraft UUID（可带或不带连字符）。 */
    private String uuid;
}
