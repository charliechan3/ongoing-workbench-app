package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.User;
import com.ongoing.workbench.repo.UserRepo;
import com.ongoing.workbench.service.AccountService;
import com.ongoing.workbench.util.JwtUtil;
import com.ongoing.workbench.util.UserContext;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口（匿名可访问：/status /register /login /guest；其余需登录）：
 * GET  /api/auth/status   系统是否已初始化（存在账号）+ 运行模式 mode（local|cloud）
 * POST /api/auth/register 注册（首个账号自动认领升级前的历史数据）
 * POST /api/auth/login    登录 → { token, user }
 * POST /api/auth/guest    游客体验登录 → 只读体验令牌（写接口 403，不产生任何数据）
 * GET  /api/auth/me       当前登录用户信息（刷新页面校验用）
 * PUT  /api/auth/password 修改密码
 * PUT  /api/auth/profile  修改昵称
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired private AccountService accounts;
    @Autowired private UserRepo users;
    @Autowired private JwtUtil jwt;
    @Autowired private Environment env;

    @Data
    public static class RegisterReq {
        private String username;
        private String password;
        private String nickname;
    }

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @Data
    public static class PasswordReq {
        private String oldPassword;
        private String newPassword;
    }

    @Data
    public static class ProfileReq {
        private String nickname;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("initialized", users.count() > 0);
        // 运行模式：local（本地 H2）| cloud（云端数据库），登录页按此切换底部文案
        boolean cloud = false;
        for (String p : env.getActiveProfiles()) {
            if ("cloud".equalsIgnoreCase(p)) { cloud = true; break; }
        }
        out.put("mode", cloud ? "cloud" : "local");
        return out;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
        User u = accounts.register(req.getUsername(), req.getPassword(), req.getNickname());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.sign(u.getId(), u.getUsername()));
        out.put("user", view(u));
        return out;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
        User u = accounts.login(req.getUsername(), req.getPassword());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.sign(u.getId(), u.getUsername()));
        out.put("user", view(u));
        return out;
    }

    /** 游客体验登录：发放只读体验令牌（role=guest），不创建账号、不读写任何业务数据 */
    @PostMapping("/guest")
    public Map<String, Object> guest() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", jwt.signGuest());
        out.put("user", guestView());
        out.put("guest", true);
        return out;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        String uid = UserContext.currentUserId();
        if ("guest".equals(uid)) { // 游客令牌：无对应账号行，直接回显游客身份
            Map<String, Object> out = guestView();
            out.put("ok", true);
            return out;
        }
        User u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已注销"));
        Map<String, Object> out = view(u);
        out.put("ok", true);
        return out;
    }

    @PutMapping("/password")
    public Map<String, Object> changePassword(@RequestBody PasswordReq req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
        accounts.changePassword(UserContext.currentUserId(), req.getOldPassword(), req.getNewPassword());
        return Map.of("ok", true, "message", "密码已更新");
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody ProfileReq req) {
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体为空");
        User u = accounts.updateNickname(UserContext.currentUserId(), req.getNickname());
        Map<String, Object> out = view(u);
        out.put("ok", true);
        return out;
    }

    /** 游客身份视图（无对应账号行） */
    private Map<String, Object> guestView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "guest");
        m.put("username", "guest");
        m.put("nickname", "游客");
        return m;
    }

    /** 用户公开视图（绝不外泄 passwordHash） */
    private Map<String, Object> view(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
