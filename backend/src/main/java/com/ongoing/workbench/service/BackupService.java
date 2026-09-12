package com.ongoing.workbench.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ongoing.workbench.entity.*;
import com.ongoing.workbench.repo.*;
import com.ongoing.workbench.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * 备份/恢复服务（多用户版）：
 * 所有读写都限定在"当前登录用户"自己的数据范围内，settings 取当前用户行。
 * 备份文件中的 ownerId 在导入时一律以当前用户重新盖章，跨账号还原=复制到自己空间。
 */
@Service
public class BackupService {

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

    private final ObjectMapper om = new ObjectMapper();

    public static final String APP_TAG = "ongoing-workbench";
    public static final String APP_VERSION = "1.1.0";
    private static final List<String> TYPES = List.of(
            "areas", "projects", "tasks", "actions", "todos",
            "checklist", "pomodoros", "workLogs", "notes", "media");

    /** 实体类型 → 父级引用字段 → 父级类型（用于合并时重映射） */
    private static final Map<String, List<String>> REFS = Map.of(
            "projects", List.of("areaId"),
            "tasks",    List.of("projectId", "areaId"),
            "actions",  List.of("taskId", "projectId", "areaId"),
            "todos",    List.of("projectId", "taskId"),
            "checklist", List.of("actionId", "todoId"),
            "notes",    List.of("projectId"));
    private static final Map<String, String> REF_TYPE = Map.of(
            "areaId", "areas", "projectId", "projects", "taskId", "tasks",
            "actionId", "actions", "todoId", "todos");

    /* ================= 导出 ================= */

    @Transactional(readOnly = true)
    public Map<String, Object> exportBundle() {
        String uid = owner();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("areas", areas.findByOwnerId(uid));
        data.put("projects", projects.findByOwnerId(uid));
        data.put("tasks", tasks.findByOwnerId(uid));
        data.put("actions", actions.findByOwnerId(uid));
        data.put("todos", todos.findByOwnerId(uid));
        data.put("checklist", checklist.findByOwnerId(uid));
        data.put("pomodoros", pomodoros.findByOwnerId(uid));
        data.put("workLogs", workLogs.findByOwnerId(uid));
        data.put("notes", notes.findByOwnerId(uid));
        data.put("media", media.findByOwnerId(uid));
        data.put("settings", settings.findById(uid).orElse(new Settings()));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("app", APP_TAG);
        bundle.put("format", 2);
        bundle.put("version", APP_VERSION);
        bundle.put("exportedAt", LocalDate.now().toString());
        bundle.put("data", data);
        return bundle;
    }

    /* ================= 导入校验 ================= */

    @SuppressWarnings("unchecked")
    public Map<String, Object> preview(Map<String, Object> data) {
        String uid = owner();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, String>> conflicts = new ArrayList<>();
        List<Map<String, String>> dangling = new ArrayList<>();
        for (String t : TYPES) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get(t);
            int n = list == null ? 0 : list.size();
            counts.put(t, n);
            if (list != null) {
                Set<String> exist = new HashSet<>(idsOf(t, uid));
                for (Map<String, Object> item : list) {
                    String id = str(item.get("id"));
                    if (id != null && exist.contains(id)) {
                        Map<String, String> c = new LinkedHashMap<>();
                        c.put("type", t); c.put("id", id);
                        conflicts.add(c);
                    }
                }
            }
        }
        /* 悬空关联：备份数据内部的引用，父级在备份中不存在 */
        for (String t : REFS.keySet()) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get(t);
            if (list == null) continue;
            for (Map<String, Object> item : list) {
                for (String f : REFS.get(t)) {
                    String ref = str(item.get(f));
                    if (ref == null || ref.isEmpty()) continue;
                    String pt = REF_TYPE.get(f);
                    List<Map<String, Object>> parents = (List<Map<String, Object>>) data.get(pt);
                    boolean found = parents != null && parents.stream().anyMatch(p -> ref.equals(str(p.get("id"))));
                    if (!found) {
                        Map<String, String> d = new LinkedHashMap<>();
                        d.put("type", t); d.put("id", str(item.get("id"))); d.put("field", f); d.put("ref", ref);
                        dangling.add(d);
                    }
                }
            }
        }
        out.put("counts", counts);
        out.put("conflicts", conflicts);
        out.put("dangling", dangling);
        return out;
    }

    /* ================= 恢复 ================= */

    @SuppressWarnings("unchecked")
    @Transactional
    public Map<String, Object> apply(Map<String, Object> data, String mode) {
        String uid = owner();
        if ("overwrite".equals(mode)) {
            wipeOwn(uid);
            for (String t : TYPES) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) data.get(t);
                if (list == null) continue;
                for (Map<String, Object> item : list) saveTyped(t, item, uid);
            }
            restoreSettings((Map<String, Object>) data.get("settings"), uid);
            return msg("已覆盖恢复：当前空间数据被完整替换为备份数据。");
        }

        /* 合并：ID 冲突自动换新并重映射关联 */
        Map<String, Map<String, String>> idMaps = new HashMap<>();
        for (String t : TYPES) idMaps.put(t, new HashMap<>());
        for (String t : TYPES) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get(t);
            if (list == null) continue;
            Set<String> exist = new HashSet<>(idsOf(t, uid));
            for (Map<String, Object> item : list) {
                String oldId = str(item.get("id"));
                if (oldId == null) continue;
                idMaps.get(t).put(oldId, exist.contains(oldId) ? uid(t) : oldId);
            }
        }
        int added = 0;
        for (String t : TYPES) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) data.get(t);
            if (list == null) continue;
            for (Map<String, Object> item : list) {
                Map<String, Object> copy = new LinkedHashMap<>(item);
                String newId = idMaps.get(t).get(str(copy.get("id")));
                if (newId == null) continue;
                copy.put("id", newId);
                for (String f : REFS.getOrDefault(t, List.of())) {
                    String ref = str(copy.get(f));
                    if (ref != null && !ref.isEmpty()) {
                        String mapped = idMaps.get(REF_TYPE.get(f)).get(ref);
                        if (mapped != null) copy.put(f, mapped);
                    }
                }
                if (exists(t, newId, uid)) continue;
                saveTyped(t, copy, uid);
                added++;
            }
        }
        restoreSettings((Map<String, Object>) data.get("settings"), uid);
        return msg("合并恢复完成：保留现有数据并合并备份（ID 冲突项已自动换新），新增 " + added + " 条记录。");
    }

    private void restoreSettings(Map<String, Object> s, String uid) {
        if (s == null) return;
        Settings cur = settings.findById(uid).orElseGet(() -> { Settings x = new Settings(); x.setId(uid); return x; });
        Object pm = s.get("pomodoroMin");
        Object bm = s.get("breakMin");
        if (pm != null) cur.setPomodoroMin(Integer.parseInt(pm.toString()));
        if (bm != null) cur.setBreakMin(Integer.parseInt(bm.toString()));
        settings.save(cur);
    }

    /** 只清空当前用户空间的数据（不动其他账号、不动全局） */
    private void wipeOwn(String uid) {
        areas.deleteByOwnerId(uid);
        projects.deleteByOwnerId(uid);
        tasks.deleteByOwnerId(uid);
        actions.deleteByOwnerId(uid);
        todos.deleteByOwnerId(uid);
        checklist.deleteByOwnerId(uid);
        pomodoros.deleteByOwnerId(uid);
        workLogs.deleteByOwnerId(uid);
        notes.deleteByOwnerId(uid);
        media.deleteByOwnerId(uid);
    }

    private void saveTyped(String t, Map<String, Object> item, String uid) {
        item = item == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item);
        item.put("ownerId", uid); // 导入一律归到当前用户，忽略文件里的原始 ownerId
        switch (t) {
            case "areas" -> areas.save(om.convertValue(item, Area.class));
            case "projects" -> projects.save(om.convertValue(item, Project.class));
            case "tasks" -> tasks.save(om.convertValue(item, Task.class));
            case "actions" -> actions.save(om.convertValue(item, Action.class));
            case "todos" -> todos.save(om.convertValue(item, Todo.class));
            case "checklist" -> checklist.save(om.convertValue(item, ChecklistItem.class));
            case "pomodoros" -> pomodoros.save(om.convertValue(item, Pomodoro.class));
            case "workLogs" -> workLogs.save(om.convertValue(item, WorkLog.class));
            case "notes" -> notes.save(om.convertValue(item, Note.class));
            case "media" -> media.save(om.convertValue(item, Media.class));
        }
    }

    private boolean exists(String t, String id, String uid) {
        return switch (t) {
            case "areas" -> areas.existsByIdAndOwnerId(id, uid);
            case "projects" -> projects.existsByIdAndOwnerId(id, uid);
            case "tasks" -> tasks.existsByIdAndOwnerId(id, uid);
            case "actions" -> actions.existsByIdAndOwnerId(id, uid);
            case "todos" -> todos.existsByIdAndOwnerId(id, uid);
            case "checklist" -> checklist.existsByIdAndOwnerId(id, uid);
            case "pomodoros" -> pomodoros.existsByIdAndOwnerId(id, uid);
            case "workLogs" -> workLogs.existsByIdAndOwnerId(id, uid);
            case "notes" -> notes.existsByIdAndOwnerId(id, uid);
            case "media" -> media.existsByIdAndOwnerId(id, uid);
            default -> false;
        };
    }

    private List<String> idsOf(String t, String uid) {
        return switch (t) {
            case "areas" -> areas.findByOwnerId(uid).stream().map(Area::getId).toList();
            case "projects" -> projects.findByOwnerId(uid).stream().map(Project::getId).toList();
            case "tasks" -> tasks.findByOwnerId(uid).stream().map(Task::getId).toList();
            case "actions" -> actions.findByOwnerId(uid).stream().map(Action::getId).toList();
            case "todos" -> todos.findByOwnerId(uid).stream().map(Todo::getId).toList();
            case "checklist" -> checklist.findByOwnerId(uid).stream().map(ChecklistItem::getId).toList();
            case "pomodoros" -> pomodoros.findByOwnerId(uid).stream().map(Pomodoro::getId).toList();
            case "workLogs" -> workLogs.findByOwnerId(uid).stream().map(WorkLog::getId).toList();
            case "notes" -> notes.findByOwnerId(uid).stream().map(Note::getId).toList();
            case "media" -> media.findByOwnerId(uid).stream().map(Media::getId).toList();
            default -> List.of();
        };
    }

    /* ================= Markdown 导出 ================= */

    @Transactional(readOnly = true)
    public String exportMarkdown() {
        String uid = owner();
        List<Area> myAreas = areas.findByOwnerId(uid);
        List<Project> myProjects = projects.findByOwnerId(uid);
        List<Task> myTasks = tasks.findByOwnerId(uid);
        List<Action> myActions = actions.findByOwnerId(uid);
        List<Todo> myTodos = todos.findByOwnerId(uid);
        List<Note> myNotes = notes.findByOwnerId(uid);
        List<Media> myMedia = media.findByOwnerId(uid);
        List<Pomodoro> myPomos = pomodoros.findByOwnerId(uid);
        List<WorkLog> myLogs = workLogs.findByOwnerId(uid);

        StringBuilder sb = new StringBuilder();
        sb.append("# 进行时-个人工作台 · 数据导出\n\n");
        sb.append("> 导出时间：").append(LocalDate.now()).append(" ｜ 应用版本：").append(APP_VERSION).append("\n\n");
        sb.append("## 一、总览\n\n");
        sb.append("- 项目 ").append(myProjects.size())
          .append(" 个 ｜ 任务 ").append(myTasks.size()).append(" 个 ｜ 行动 ").append(myActions.size()).append(" 个\n");
        sb.append("- 待办 ").append(myTodos.size()).append(" 条 ｜ 工作笔记 ").append(myNotes.size())
          .append(" 篇 ｜ 影视/图书 ").append(myMedia.size()).append(" 部 ｜ 番茄钟 ").append(myPomos.size()).append(" 个\n\n");
        for (Area a : myAreas) {
            sb.append("## 二、区域：").append(a.getName()).append("\n\n");
            if (a.getNote() != null && !a.getNote().isBlank()) sb.append("> ").append(a.getNote()).append("\n\n");
            for (Project p : myProjects) {
                if (!a.getId().equals(p.getAreaId())) continue;
                sb.append("### 📦 项目：").append(p.getName()).append(" ［").append(statusLabel(p.getStatus()))
                  .append("｜进度 ").append(projectProgress(p, myTasks, myActions)).append("%］\n");
                if (p.getDesc() != null && !p.getDesc().isBlank()) sb.append("- ").append(p.getDesc()).append("\n");
                for (Task t : myTasks) {
                    if (!p.getId().equals(t.getProjectId())) continue;
                    sb.append("- **任务**：").append(t.getName()).append(" ［").append(statusLabel(t.getStatus()))
                      .append("｜进度 ").append(taskProgress(t, myActions)).append("%］\n");
                    for (Action ac : myActions) {
                        if (!t.getId().equals(ac.getTaskId())) continue;
                        sb.append("  - [").append("done".equals(ac.getStatus()) ? "x" : " ").append("] 行动：").append(ac.getName()).append("\n");
                    }
                }
                for (Action ac : myActions) {
                    if (!p.getId().equals(ac.getProjectId()) || ac.getTaskId() != null) continue;
                    sb.append("- [").append("done".equals(ac.getStatus()) ? "x" : " ").append("] 行动：").append(ac.getName()).append("\n");
                }
            }
        }
        sb.append("\n## 三、待办（近 7 天）\n\n");
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 7; i++) {
            String d = today.minusDays(i).toString();
            List<Todo> list = myTodos.stream().filter(t -> d.equals(t.getDate())).toList();
            if (list.isEmpty()) continue;
            sb.append("### ").append(d).append("\n");
            for (Todo t : list) sb.append("- [").append("done".equals(t.getStatus()) ? "x" : " ").append("] ").append(t.getText()).append("\n");
        }
        sb.append("\n## 四、工作笔记\n\n");
        for (Note n : myNotes) {
            Project p = n.getProjectId() == null ? null : myProjects.stream().filter(x -> x.getId().equals(n.getProjectId())).findFirst().orElse(null);
            sb.append("### ").append(n.getTitle()).append(p != null ? "（" + p.getName() + "）" : "").append("\n\n");
            sb.append(n.getContent() == null || n.getContent().isBlank() ? "(空)" : n.getContent()).append("\n\n");
        }
        sb.append("## 五、图书影视\n\n");
        for (Media m : myMedia) {
            sb.append("- 「").append(m.getTitle()).append("」").append(mediaStatusLabel(m.getStatus()))
              .append(m.getEndDate() != null && !m.getEndDate().isBlank() ? "｜完成于 " + m.getEndDate() : "")
              .append(m.getRating() != null ? "｜评分 " + m.getRating() + "/5" : "").append("\n");
        }
        return sb.toString();
    }

    /* ---------- 进度计算（与前端保持一致） ---------- */
    private double projectProgress(Project p, List<Task> tsAll, List<Action> asAll) {
        if ("done".equals(p.getStatus())) return 100;
        List<Task> ts = tsAll.stream().filter(t -> p.getId().equals(t.getProjectId()) && !"abandoned".equals(t.getStatus())).toList();
        List<Action> as = asAll.stream().filter(a -> p.getId().equals(a.getProjectId()) && a.getTaskId() == null && !"abandoned".equals(a.getStatus())).toList();
        if (ts.isEmpty() && as.isEmpty()) return 0;
        double sum = ts.stream().mapToDouble(t -> taskProgress(t, asAll)).sum();
        for (Action a : as) sum += "done".equals(a.getStatus()) ? 100 : 0;
        return Math.round(sum / (ts.size() + as.size()));
    }

    private double taskProgress(Task t, List<Action> asAll) {
        if (t.getProgress() != null) return Math.min(100, Math.max(0, t.getProgress()));
        if ("done".equals(t.getStatus())) return 100;
        List<Action> as = asAll.stream().filter(a -> t.getId().equals(a.getTaskId()) && !"abandoned".equals(a.getStatus())).toList();
        if (as.isEmpty()) return 0;
        return Math.round(as.stream().filter(a -> "done".equals(a.getStatus())).count() * 100.0 / as.size());
    }

    private String owner() {
        String uid = UserContext.currentUserId();
        if (uid == null) throw new IllegalStateException("未登录，无法访问个人数据");
        return uid;
    }

    private String statusLabel(String s) {
        return switch (s == null ? "" : s) {
            case "planned" -> "未开始";
            case "in_progress" -> "进行中";
            case "done" -> "已完成";
            case "paused" -> "已暂停";
            case "abandoned" -> "已放弃";
            default -> "—";
        };
    }

    private String mediaStatusLabel(String s) {
        return switch (s == null ? "" : s) {
            case "wish" -> "想看";
            case "doing" -> "进行中";
            case "done" -> "已完成";
            case "paused" -> "暂停";
            case "quit" -> "放弃";
            default -> "—";
        };
    }

    private static String uid(String prefix) {
        return prefix + "_" + System.nanoTime() + (int) (Math.random() * 100000);
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private Map<String, Object> msg(String m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", m);
        return out;
    }
}
