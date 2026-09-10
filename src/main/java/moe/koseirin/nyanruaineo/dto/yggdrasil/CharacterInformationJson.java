package moe.koseirin.nyanruaineo.dto.yggdrasil;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** Yggdrasil 角色信息（{@code id}/{@code name}/{@code properties}）。 */
@Getter
@Setter
public class CharacterInformationJson {

    private String id;
    private String name;
    private List<Property> properties;

    public CharacterInformationJson() {
    }

    public CharacterInformationJson(String id, String name, List<Property> properties) {
        this.id = id;
        this.name = name;
        this.properties = properties;
    }
}
