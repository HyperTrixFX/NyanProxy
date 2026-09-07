package moe.koseirin.nyanruaineo.dto;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** 管理面板创建/更新后端子服务器鉴权凭据的请求体。 */
@Getter
@Setter
public class ServerCredentialDTO {
    /** 关联的 BackendServer.uid（创建时必填）。 */
    private String uid;
    /** 展示名（可选，缺省取 BackendServer.name）。 */
    private String serverName;
    /** 是否启用（仅更新时生效）。 */
    private Boolean enabled;
    /** 是否重置令牌（仅更新时生效，重置后返回一次性新令牌）。 */
    private Boolean regenerate;
}
