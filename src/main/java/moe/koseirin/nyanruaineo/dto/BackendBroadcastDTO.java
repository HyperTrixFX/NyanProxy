package moe.koseirin.nyanruaineo.dto;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** v7 后端委托广播的请求体。 */
@Getter
@Setter
public class BackendBroadcastDTO {
    /** 广播消息内容。 */
    private String message;
}
