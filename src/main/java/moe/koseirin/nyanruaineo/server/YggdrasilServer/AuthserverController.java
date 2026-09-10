package moe.koseirin.nyanruaineo.server.YggdrasilServer;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.services.YggdrasilAuthenticateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yggdrasil authserver 端点（authenticate / refresh / validate）。
 * 业务逻辑 {@link YggdrasilAuthenticateService}。
 */
@RestController
@RequestMapping("api/yggdrasil/authserver")
public class AuthserverController {

    private final YggdrasilAuthenticateService authenticateService;

    public AuthserverController(YggdrasilAuthenticateService authenticateService) {
        this.authenticateService = authenticateService;
    }

    @PostMapping("authenticate")
    public ResponseEntity<?> authenticate(@RequestBody(required = false) String data, HttpServletRequest request) {
        return authenticateService.authenticate(data, request);
    }

    @PostMapping("refresh")
    public ResponseEntity<?> refresh(@RequestBody(required = false) String data) {
        return authenticateService.refresh(data);
    }

    @PostMapping("validate")
    public ResponseEntity<?> validate(@RequestBody(required = false) String data) {
        return authenticateService.validate(data);
    }
}
