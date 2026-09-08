package moe.koseirin.nyanruaineo.server.YggdrasilServer;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.services.YggdrasilTextureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Yggdrasil textures 端点（皮肤 / 披风上传）。
 * 业务逻辑 {@link YggdrasilTextureService}。
 */
@RestController
@RequestMapping("api/yggdrasil/textures")
public class TexturesController {

    private final YggdrasilTextureService textureService;

    public TexturesController(YggdrasilTextureService textureService) {
        this.textureService = textureService;
    }

    @PutMapping("skin")
    public ResponseEntity<?> putSkin(@RequestParam(value = "skin", required = false) MultipartFile skin,
                                     @RequestParam(value = "model", required = false) String model,
                                     HttpServletRequest request) throws Exception {
        return textureService.putSkin(skin, model, request);
    }

    @PutMapping("cape")
    public ResponseEntity<?> putCape(@RequestParam(value = "cape", required = false) MultipartFile cape,
                                     HttpServletRequest request) throws Exception {
        return textureService.putCape(cape, request);
    }
}
