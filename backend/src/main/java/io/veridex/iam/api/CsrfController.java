package io.veridex.iam.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 暴露当前请求的 CSRF token，供 SPA 在登录后重新获取被认证流程清除的 token。
 * 返回的是明文 token（可回传 X-XSRF-TOKEN），不包含任何敏感数据。
 */
@RestController
@RequestMapping("/api/auth")
public class CsrfController {

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        Object attribute = request.getAttribute(DeferredCsrfToken.class.getName());
        String token = attribute instanceof DeferredCsrfToken deferred ? deferred.get().getToken() : "";
        return Map.of("token", token);
    }
}
