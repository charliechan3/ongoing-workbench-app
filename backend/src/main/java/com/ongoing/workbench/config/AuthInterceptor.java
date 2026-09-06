package com.ongoing.workbench.config;

import com.ongoing.workbench.util.JwtUtil;
import com.ongoing.workbench.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * JWT 鉴权拦截器：拦截 /api/**（登录/注册/游客/系统状态等匿名端点除外）。
 * 校验 Authorization: Bearer <token>，通过后把 userId 写入 UserContext 供控制器使用。
 * 游客令牌（role=guest）只放行 GET/HEAD 浏览类请求，写请求一律 403（游客不产生任何数据）。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final String GUEST_ID = "guest";

    @Autowired
    private JwtUtil jwt;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true; // CORS 预检
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
        Map<String, Object> claims = jwt.verifyClaims(token);
        if (claims == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"未登录或登录已过期，请重新登录\"}");
            return false;
        }
        if (isGuest(claims) && !"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"游客模式仅可体验浏览，数据不会被保存；注册登录后才能保存数据\"}");
            return false;
        }
        UserContext.set(String.valueOf(claims.get("sub")));
        return true;
    }

    private boolean isGuest(Map<String, Object> claims) {
        return GUEST_ID.equals(claims.get("role")) || GUEST_ID.equals(String.valueOf(claims.get("sub")));
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
