package moe.koseirin.nyanruaineo.dto.yggdrasil;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** Yggdrasil 属性（{@code name}/{@code value}/{@code signature}）。 */
@Getter
@Setter
public class Property {

    private String name;
    private String value;
    private String signature;

    public Property() {
    }

    public Property(String name, String value, String signature) {
        this.name = name;
        this.value = value;
        this.signature = signature;
    }
}
