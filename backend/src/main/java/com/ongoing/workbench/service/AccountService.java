package com.ongoing.workbench.service;

import com.ongoing.workbench.entity.*;
import com.ongoing.workbench.repo.*;
import com.ongoing.workbench.util.Passwords;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 账号服务：
 * - 注册：全库没有任何账号时，注册者成为首个账号并认领全部"无归属"的历史数据
 *   （升级前种子/旧单用户数据 ownerId 为 null，迁移后归首个注册账号所有）
 * - 登录 / 改密 / 改昵称
 */
@Service
public class AccountService {

    @Autowired private UserRepo users;
    @Autowired private AreaRepo areas;
    @Autowired private ProjectRepo projects;
    @Autowired private TaskRepo tasks;
    @Autowired private ActionRepo actions;
    @Autowired private TodoRepo todos;
    @Autowired private PomodoroRepo pomodoros;
    @Autowired private WorkLogRepo workLogs;
    @Autowired private NoteRepo notes;
    @Autowired private MediaRepo media;
    @Autowired private ChecklistItemRepo checklist;
    @Autowired private SettingsRepo settings;

    @Transactional
    public User register(String username, String password, String nickname) {
        String uname = username == null ? "" : username.trim();
        if (uname.length() < 2 || uname.length() > 40) throw bad("用户名需 2~40 个字符");
        if (password == null || password.length() < 6 || password.length() > 64) throw bad("密码需 6~64 位");
        if (users.existsByUsername(uname)) throw bad("用户名已被占用");
        boolean firstAccount = users.count() == 0;
        User u = new User();
        u.setId(UUID.randomUUID().toString());
        u.setUsername(uname);
        u.setPasswordHash(Passwords.hash(password));
        String nick = nickname == null ? "" : nickname.trim();
        u.setNickname(nick.isEmpty() ? uname : nick);
        u.setCreatedAt(LocalDate.now().toString());
        users.save(u);
        if (firstAccount) adoptLegacy(u.getId());
        seedDefaultAreas(u.getId());
        return u;
    }

    /**
     * 新账号默认区域：工作项目 / 学习计划（与游客种子同名同序）。
     * 仅当该账号名下还没有区域时才注入——首账号可能刚认领了历史数据，不重复加。
     */
    private void seedDefaultAreas(String uid) {
        if (!areas.findByOwnerId(uid).isEmpty()) return;
        String today = LocalDate.now().toString();
        Area work = new Area();
        work.setId("area_" + UUID.randomUUID());
        work.setName("工作项目");
        work.setNote("");
        work.setSort(0);
        work.setCreatedAt(today);
        work.setOwnerId(uid);
        areas.save(work);
        Area study = new Area();
        study.setId("area_" + UUID.randomUUID());
        study.setName("学习计划");
        study.setNote("");
        study.setSort(1);
        study.setCreatedAt(today);
        study.setOwnerId(uid);
        areas.save(study);
    }

    /** 认领无归属的历史数据（升级迁移）：业务表 ownerId=null → 首账号；settings 主行 id=main → 首账号 */
    @Transactional
    public void adoptLegacy(String uid) {
        adoptRepo(areas, uid); adoptRepo(projects, uid); adoptRepo(tasks, uid);
        adoptRepo(actions, uid); adoptRepo(todos, uid); adoptRepo(pomodoros, uid); adoptRepo(workLogs, uid);
        adoptRepo(notes, uid); adoptRepo(media, uid); adoptRepo(checklist, uid);
        // settings 历史单行(id=main) → 复制给首账号（不能复用被删实体做 merge，先拷字段到新实体）
        settings.findById("main").ifPresent(old -> {
            Settings n = new Settings();
            n.setId(uid);
            n.setPomodoroMin(old.getPomodoroMin());
            n.setBreakMin(old.getBreakMin());
            n.setDailyGoalMinutes(old.getDailyGoalMinutes());
            n.setAutoPomoOnTodo(old.getAutoPomoOnTodo());
            n.setAutoPomoOnAction(old.getAutoPomoOnAction());
            settings.delete(old);
            settings.save(n);
        });
    }

    @Transactional
    public User login(String username, String password) {
        String uname = username == null ? "" : username.trim();
        User u = users.findByUsername(uname).orElse(null);
        if (u == null || !Passwords.verify(password == null ? "" : password, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return u;
    }

    @Transactional
    public User changePassword(String uid, String oldPassword, String newPassword) {
        User u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!Passwords.verify(oldPassword == null ? "" : oldPassword, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) throw bad("新密码需 6~64 位");
        u.setPasswordHash(Passwords.hash(newPassword));
        return users.save(u);
    }

    @Transactional
    public User updateNickname(String uid, String nickname) {
        User u = users.findById(uid).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String nick = nickname == null ? "" : nickname.trim();
        u.setNickname(nick.isEmpty() || nick.length() > 40 ? u.getUsername() : nick);
        return users.save(u);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void adoptRepo(OwnedRepo repo, String uid) {
        List orphan = repo.findByOwnerIdIsNull();
        if (orphan.isEmpty()) return;
        for (Object o : orphan) ((OwnedEntity) o).setOwnerId(uid);
        repo.saveAll(orphan);
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
