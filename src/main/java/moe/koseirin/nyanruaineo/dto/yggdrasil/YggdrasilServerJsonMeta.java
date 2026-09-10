package moe.koseirin.nyanruaineo.dto.yggdrasil;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** Yggdrasil 服务根元数据 meta 部分。 */
@Getter
@Setter
public class YggdrasilServerJsonMeta {
    private String implementationName;
    private String implementationVersion;
    private String serverName;
    private YggdrasilServerJsonLinks links;
    private boolean feature_non_email_login;
}
