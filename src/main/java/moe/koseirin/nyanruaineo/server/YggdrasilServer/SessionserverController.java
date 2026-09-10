package moe.koseirin.nyanruaineo.server.YggdrasilServer;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.servlet.http.HttpServletRequest;
import moe.koseirin.nyanruaineo.services.YggdrasilSessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yggdrasil sessionserver 端点（join / hasJoined / profile）。
 * 业务逻辑 {@link YggdrasilSessionService}。
 */
@RestController
@RequestMapping("api/yggdrasil/sessionserver/session/minecraft")
public class SessionserverController {

    private final YggdrasilSessionService sessionService;

    public SessionserverController(YggdrasilSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("join")
    public ResponseEntity<?> join(@RequestBody(required = false) String data, HttpServletRequest request) {
        return sessionService.join(data, request);
    }

    @GetMapping("hasJoined")
    public ResponseEntity<?> hasJoined(HttpServletRequest request) {
        return sessionService.hasJoined(request);
    }

    @GetMapping({"profile/{uuid}", "profile/*", "profile"})
    public ResponseEntity<?> profile(@PathVariable String uuid, HttpServletRequest request) {
        return sessionService.profile(uuid, request);
    }
}
