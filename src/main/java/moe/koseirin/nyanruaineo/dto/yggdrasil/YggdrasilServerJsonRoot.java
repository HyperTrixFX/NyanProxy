package moe.koseirin.nyanruaineo.dto.yggdrasil;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** Yggdrasil 服务根元数据（authlib-injector 的 {@code /} 响应）。 */
@Getter
@Setter
public class YggdrasilServerJsonRoot {
    private YggdrasilServerJsonMeta meta;
    private String[] skinDomains;
    private String signaturePublickey;
}
