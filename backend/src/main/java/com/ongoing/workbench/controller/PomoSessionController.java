package com.ongoing.workbench.controller;

import com.ongoing.workbench.entity.PomoSession;
import com.ongoing.workbench.repo.PomoSessionRepo;
import com.ongoing.workbench.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 进行中的番茄钟会话（多端同步）。
 *
 * <p>前端把这里当"当前正在专注的那一颗"的唯一真相：开始 → PUT，取消 → DELETE，
 * 到点入账 → POST /finish（原子认领），任意端每隔十几秒 GET 一次对账。</p>
 *
 * <p>响应统一带上 serverNow，让客户端把服务器时间换算成本地时钟口径，
 * 避免手机/电脑时钟有偏差时两端显示的剩余时间不一样。</p>
 */
@RestController
@RequestMapping("/api/pomo-session")
public class PomoSessionController {

    /** 到点后超过这个时长还没被任何一端结算的会话视为脏数据（与前端刷新恢复的 2 小时口径一致） */
    private static final long STALE_MS = 2 * 3600 * 1000L;

    @Autowired
    private PomoSessionRepo repo;

    /** 服务端快照：session 为 null 表示当前没有进行中的会话 */
    public record Snapshot(PomoSession session, long serverNow, boolean claimed) {}

    public record StartReq(Integer minutes, String todoId, String sid) {}

    public record FinishReq(String sid) {}

    @GetMapping
    public Snapshot get() {
        String uid = uid();
        long now = System.currentTimeMillis();
        if ("guest".equals(uid)) return new Snapshot(null, now, false); // 游客不产生任何数据
        PomoSession s = repo.findById(uid).orElse(null);
        if (s != null && s.getEndAt() != null && now - s.getEndAt() > STALE_MS) {
            repo.delete(s);
            s = null;
        }
        return new Snapshot(s, now, false);
    }

    /** 开始（或替换）会话：结束时间由服务端算，客户端只给时长与绑定目标 */
    @PutMapping
    public Snapshot start(@RequestBody(required = false) StartReq req) {
        String uid = uid();
        int minutes = req == null || req.minutes() == null ? 25 : req.minutes();
        minutes = Math.max(1, minutes); // 防止负数/0 造成立刻到点
        long now = System.currentTimeMillis();
        PomoSession s = new PomoSession();
        s.setId(uid);
        s.setOwnerId(uid);
        s.setSid(req == null ? null : req.sid());
        s.setMinutes(minutes);
        s.setTodoId(req == null ? null : req.todoId());
        s.setStartAt(now);
        s.setEndAt(now + minutes * 60000L);
        s.setUpdatedAt(now);
        repo.save(s);
        return new Snapshot(s, now, false);
    }

    /** 取消会话（不入账）。sid 为客户端会话标识，避免误删另一端新开的番茄 */
    @DeleteMapping
    @Transactional
    public Snapshot cancel(@RequestParam(required = false) String sid) {
        String uid = uid();
        long now = System.currentTimeMillis();
        if (sid != null && !sid.isBlank()) repo.claimBySid(uid, uid, sid);
        else repo.claimById(uid, uid);
        repo.flush();
        return new Snapshot(null, now, false);
    }

    /**
     * 结算会话：删到行的那一端 claimed=true（由它写番茄记录），其余端 claimed=false。
     * 删除放在同一事务里，多端同时到点也只会有一个请求删到行。
     */
    @PostMapping("/finish")
    @Transactional
    public Snapshot finish(@RequestBody(required = false) FinishReq req) {
        String uid = uid();
        long now = System.currentTimeMillis();
        PomoSession s = repo.findById(uid).orElse(null);
        if (s == null) return new Snapshot(null, now, false);
        int hit = req != null && req.sid() != null && !req.sid().isBlank()
                ? repo.claimBySid(uid, uid, req.sid())
                : repo.claimById(uid, uid);
        repo.flush();
        return new Snapshot(hit > 0 ? s : null, now, hit > 0);
    }

    private String uid() {
        String u = UserContext.currentUserId();
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        return u;
    }
}
