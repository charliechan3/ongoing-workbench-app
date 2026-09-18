package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.Settings;
import com.ongoing.workbench.repo.SettingsRepo;
import com.ongoing.workbench.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 偏好设置：每个用户一行，主键 = userId（多用户数据隔离）。
 * 历史单行（id=main）在首个账号注册时已迁移到该账号 id 下。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    @Autowired
    private SettingsRepo repo;

    @GetMapping
    public Settings get() {
        String uid = uid();
        if ("guest".equals(uid)) { // 游客模式：返回默认偏好但不落库（游客不产生任何数据）
            return normalize(new Settings());
        }
        Settings s = repo.findById(uid).orElseGet(() -> {
            Settings n = new Settings();
            n.setId(uid);
            return repo.save(n);
        });
        // 新列对已存在的行是 NULL，这里统一兜底，避免前端输入框出现空白
        return normalize(s);
    }

    private Settings normalize(Settings s) {
        if (s.getPomodoroMin() == null || s.getPomodoroMin() < 1) s.setPomodoroMin(25);
        if (s.getBreakMin() == null || s.getBreakMin() < 1) s.setBreakMin(5);
        if (s.getDailyGoalMinutes() == null || s.getDailyGoalMinutes() < 0) s.setDailyGoalMinutes(0);
        if (s.getAutoPomoOnTodo() == null) s.setAutoPomoOnTodo(false);
        if (s.getAutoPomoOnAction() == null) s.setAutoPomoOnAction(false);
        if (s.getShowPomoInTitle() == null) s.setShowPomoInTitle(false);
        return s;
    }

    @PutMapping
    public Settings put(@RequestBody Settings s) {
        s.setId(uid());
        return repo.save(normalize(s));
    }

    private String uid() {
        String u = UserContext.currentUserId();
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        return u;
    }
}
