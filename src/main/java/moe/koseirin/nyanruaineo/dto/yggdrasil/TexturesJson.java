package moe.koseirin.nyanruaineo.dto.yggdrasil;

/*
 * @author KoseiRin_
 * awa
 */

import lombok.Getter;
import lombok.Setter;

/** Yggdrasil 纹理（皮肤/披风）描述结构。 */
public class TexturesJson {

    @Getter
    @Setter
    public static class TextureMetadata {
        private String model;

        public TextureMetadata(String model) {
            this.model = model;
        }
    }

    @Getter
    @Setter
    public static class SkinTexture {
        private String url;
        private TextureMetadata metadata;

        public SkinTexture(String url, TextureMetadata metadata) {
            this.url = url;
            this.metadata = metadata;
        }
    }
}
