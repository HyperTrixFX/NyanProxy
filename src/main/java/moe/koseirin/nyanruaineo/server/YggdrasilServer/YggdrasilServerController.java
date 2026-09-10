package moe.koseirin.nyanruaineo.server.YggdrasilServer;

/*
 * @author KoseiRin_
 * awa
 */

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import moe.koseirin.nyanruaineo.services.YggdrasilServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Yggdrasil 服务根端点（authlib-injector 元数据）与账号开通端点。
 * 业务逻辑 {@link YggdrasilServerService}。
 */
@RestController
@RequestMapping("api/yggdrasil")
public class YggdrasilServerController {

    private final YggdrasilServerService yggdrasilServerService;

    public YggdrasilServerController(YggdrasilServerService yggdrasilServerService) {
        this.yggdrasilServerService = yggdrasilServerService;
    }

    @GetMapping({"", "/"})
    public Object root(HttpServletResponse response, HttpServletRequest request) {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String requestURL = request.getRequestURL().toString();
        String queryString = request.getQueryString();
        String fullURL = requestURL + (queryString != null ? "?" + queryString : "");
        response.setHeader("X-Authlib-Injector-API-Location", fullURL);
        return yggdrasilServerService.rootMeta();
    }

    @PostMapping("open/account")
    public ResponseEntity<?> openAccount(HttpServletRequest request) {
        return yggdrasilServerService.openAccount(request.getHeader("Authorization"));
    }
}
