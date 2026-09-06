package com.ongoing.workbench.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SPA 静态托管回退（用于「前端构建产物打进后端 jar」的同源部署场景）：
 * <p>
 * Vue Router 使用 history 模式，直接访问或刷新 /ongoing、/settings 等路径时，
 * 后端并没有对应 Controller——这里把「无扩展名、非 /api 前缀」的 GET/HEAD 请求
 * 转发到 /index.html，由前端路由接管。
 * <p>
 * 放行规则：
 * <ul>
 *   <li>/api/** → 原样放行（交给 Controller + AuthInterceptor）</li>
 *   <li>带 "." 的路径（/assets/*.js、/favicon.ico 等）→ 交给静态资源处理</li>
 *   <li>ERROR 转发（如 404 后的 /error 流程）不再二次回退，避免循环</li>
 * </ul>
 */
@Component
public class SpaFallbackFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();

        boolean browserNavigation = "GET".equals(method) || "HEAD".equals(method);
        if (browserNavigation
                && !uri.startsWith("/api")
                && !uri.equals("/")
                && !uri.contains(".")
                && !"ERROR".equals(request.getDispatcherType().name())) {
            request.getRequestDispatcher("/index.html").forward(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
