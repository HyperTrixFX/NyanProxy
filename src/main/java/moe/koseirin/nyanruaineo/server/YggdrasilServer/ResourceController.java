package moe.koseirin.nyanruaineo.server.YggdrasilServer;

/*
 * @author KoseiRin_
 * awa
 */

import moe.koseirin.nyanruaineo.services.YggdrasilResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Yggdrasil 静态资源端点（头像 / 纹理读取）。
 * 业务逻辑 {@link YggdrasilResourceService}。
 */
@RestController
@RequestMapping("api/zako/res/{type}/{data}")
public class ResourceController {

    private final YggdrasilResourceService resourceService;

    public ResourceController(YggdrasilResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @GetMapping
    public ResponseEntity<?> get(@PathVariable String type, @PathVariable String data) throws IOException {
        return resourceService.getResource(type, data);
    }
}
