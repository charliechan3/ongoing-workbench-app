package com.ongoing.workbench.config;

import com.ongoing.workbench.entity.*;
import com.ongoing.workbench.repo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Component
public class DataInitializer implements CommandLineRunner {

    @Autowired private AreaRepo areas;
    @Autowired private ProjectRepo projects;
    @Autowired private TaskRepo tasks;
    @Autowired private ActionRepo actions;
    @Autowired private TodoRepo todos;
    @Autowired private PomodoroRepo pomodoros;
    @Autowired private WorkLogRepo workLogs;
    @Autowired private NoteRepo notes;
    @Autowired private MediaRepo media;
    @Autowired private SettingsRepo settings;

    @Override
    @Transactional
    public void run(String... args) {
        if (areas.count() > 0) return;
        seed();
    }

    private void seed() {
        LocalDate today = LocalDate.now();
        String now = today.toString();
        String uid = "id_" + System.nanoTime();

        /* 区域 */
        Area area = new Area();
        area.setId("area_career");
        area.setName("职业发展");
        area.setNote("");
        area.setCreatedAt(now);
        areas.save(area);

        /* 项目 */
        Project p1 = project("proj_job", "area_career", "找工作", "简历、作品集与面试准备", "in_progress", "P1", 60);
        Project p2 = project("proj_bigdata", "area_career", "大数据技术栈", "Doris / Kafka / Flink / 数仓建模", "in_progress", "P1", 30);
        Project p3 = project("proj_ai", "area_career", "AI 应用", "LLM 应用开发、RAG、Agent、Codex", "in_progress", "P1", 20);
        Project p4 = project("proj_design", "area_career", "产品设计", "需求分析、原型、方法论沉淀", "in_progress", "P2", 45);
        Project p5 = project("proj_blog", "area_career", "技术博客", "建立个人技术博客，输出学习内容", "planned", "P2", 7);
        Project p6 = project("proj_game", "area_career", "开发一个微信小游戏", "以 AI 辅助开发的微信小游戏", "planned", "P2", 3);

        /* 任务 */
        Task t1 = task("task_doris", "proj_bigdata", "Doris 的学习", "掌握 Apache Doris 核心概念、索引与查询优化", "in_progress", "P1", 14, 12, 0);
        Task t2 = task("task_codex", "proj_ai", "Codex 的应用学习", "学习使用 Codex 辅助编码与自动化", "in_progress", "P1", 10, 8, 0);
        Task t3 = task("task_prio", "proj_design", "需求优先级方法论", "RICE / KANO 方法整理与练习", "in_progress", "P2", 8, 4, 0);
        Task t4 = task("task_blog", "proj_blog", "搭建技术博客框架", "选择平台与主题，确定写作流程", "planned", "P2", 2, 3, 0);

        /* 行动 */
        action("act_video", "task_doris", "proj_bigdata", "学习 Doris 的概念视频教学", "done", 13, null, null);
        action("act_index", "task_doris", "proj_bigdata", "Doris 的索引学习", "in_progress", 6, Map.of("type", "weekly", "weekdays", List.of(1, 3, 5)), null);
        action("act_install", "task_codex", "proj_ai", "Codex 的下载安装", "done", 10, null, null);
        action("act_script", "task_codex", "proj_ai", "用 Codex 完成一个小脚本", "in_progress", 4, null, null);
        action("act_resume", null, "proj_job", "更新简历并投递 3 家", "in_progress", 5, null, null);
        action("act_rice", "task_prio", "proj_design", "用 RICE 给 5 个需求打分", "planned", 1, null, null);
        action("act_blogreg", "task_blog", "proj_blog", "确定博客平台并注册", "planned", 1, null, null);

        /* 待办 */
        todo("td1", "学习 Doris 的索引结构（视频 + 笔记）", today, "done", "P1", null);
        todo("td2", "用 Codex 写一个 Markdown 整理脚本", today, "todo", "P1", null);
        todo("td3", "更新简历「AI 数据产品」项目经历", today, "todo", "P1", null);
        todo("td4", "阅读 30 分钟", today, "todo", "P3", "batch_demo");
        todo("td5", "整理 Doris 概念笔记", today.minusDays(1), "done", "P1", null);
        todo("td6", "Codex 环境配置排错", today.minusDays(1), "done", "P2", null);
        todo("td7", "RICE 打分练习 ×5", today.minusDays(1), "done", "P2", null);
        todo("td8", "阅读 30 分钟", today.minusDays(1), "done", "P3", "batch_demo");
        todo("td9", "大数据技术栈学习规划", today.minusDays(2), "done", "P1", null);
        todo("td10", "阅读 30 分钟", today.minusDays(2), "todo", "P3", "batch_demo");
        todo("td11", "简历初稿 v1", today.minusDays(2), "done", "P1", null);
        todo("td12", "AI 产品资讯阅读", today.minusDays(3), "done", "P2", null);
        todo("td13", "周复盘", today.minusDays(3), "done", "P2", null);

        /* 番茄 / 工时 */
        pomo("pm1", today, "Doris 学习");
        pomo("pm2", today, "Doris 学习");
        pomo("pm3", today.minusDays(1), "Doris 学习");
        pomo("pm4", today.minusDays(1), "Doris 学习");
        pomo("pm5", today.minusDays(1), "Doris 学习");
        pomo("pm6", today.minusDays(1), "Doris 学习");
        workLog("wl1", today.minusDays(1), 30, "manual", "简历修改");
        pomo("pm7", today.minusDays(2), "学习规划");
        pomo("pm8", today.minusDays(2), "学习规划");
        pomo("pm9", today.minusDays(2), "学习规划");
        pomo("pm10", today.minusDays(3), "AI 资讯阅读");
        pomo("pm11", today.minusDays(3), "AI 资讯阅读");

        /* 笔记 */
        note("note_doris", "proj_bigdata", "Doris 学习笔记：核心概念",
                "# Doris 核心概念\n\n## 什么是 Doris\n\nApache Doris 是一款**MPP 架构**的实时分析型数据库，擅长高并发点查与实时报表。\n\n## 关键特性\n\n- 列式存储 + 向量化执行\n- 支持明细 / 聚合 / 唯一 / 主键四种模型\n- 物化视图加速查询\n\n> 下一步：深入索引与分区剪枝原理。", 1);
        note("note_codex", "proj_ai", "Codex 使用初体验",
                "# Codex 初体验\n\n今天完成了 Codex 的安装，并让它辅助写了一个脚本。\n\n## 心得\n\n1. 任务描述越具体，输出质量越高\n2. 可以逐步拆解需求，让 AI 分步实现\n3. 需要人工 review 生成的代码", 3);
        note("note_prio", "proj_design", "需求优先级方法整理",
                "# 优先级方法对比\n\n| 方法 | 核心 | 适用 |\n| --- | --- | --- |\n| RICE | 覆盖×影响×信心/成本 | 功能取舍 |\n| KANO | 需求分类 | 满意度分析 |\n\nRICE 适合量化决策，KANO 适合理解用户预期。", 5);

        /* 图书影视 */
        media("m_book1", "book", "AI 产品经理手册", "doing", 45, 20, null, 0, "重点看 AI 产品评估章节");
        media("m_movie1", "movie", "星际穿越", "wish", 0, 0, null, 0, "");
        media("m_tv1", "tv", "三体（电视剧）", "done", 100, 40, 10, 5, "很喜欢，配合原著看");
        media("m_doc1", "doc", "大数据时代", "doing", 30, 12, null, 0, "");
        media("m_book2", "book", "用数据讲故事", "wish", 0, 0, null, 0, "");

        Settings s = new Settings();
        s.setId("main"); s.setPomodoroMin(25); s.setBreakMin(5);
        settings.save(s);
    }

    private Project project(String id, String areaId, String name, String desc, String status, String prio, int startDaysAgo) {
        Project p = new Project();
        p.setId(id); p.setAreaId(areaId); p.setName(name); p.setDesc(desc);
        p.setStatus(status); p.setPriority(prio); p.setStartDate(LocalDate.now().minusDays(startDaysAgo).toString());
        p.setCreatedAt(LocalDate.now().toString());
        projects.save(p);
        return p;
    }

    private Task task(String id, String projectId, String name, String desc, String status, String prio, int startDaysAgo, int est, int cnt) {
        Task t = new Task();
        t.setId(id); t.setProjectId(projectId); t.setName(name); t.setDesc(desc);
        t.setStatus(status); t.setPriority(prio); t.setStartDate(LocalDate.now().minusDays(startDaysAgo).toString());
        t.setPomoEstimate(est); t.setPomoCount(cnt);
        t.setCreatedAt(LocalDate.now().toString());
        tasks.save(t);
        return t;
    }

    private void action(String id, String taskId, String projectId, String name, String status, int startDaysAgo, Map<String, Object> repeat, List<String> doneDates) {
        Action a = new Action();
        a.setId(id); a.setTaskId(taskId); a.setProjectId(projectId); a.setName(name);
        a.setStatus(status); a.setPriority("P2");
        a.setStartDate(LocalDate.now().minusDays(startDaysAgo).toString());
        a.setRepeat(repeat);
        a.setDoneDates(doneDates == null ? new ArrayList<>() : doneDates);
        a.setCreatedAt(LocalDate.now().toString());
        actions.save(a);
    }

    private void todo(String id, String text, LocalDate date, String status, String prio, String batchKey) {
        Todo t = new Todo();
        t.setId(id); t.setText(text); t.setDate(date.toString()); t.setStatus(status); t.setPriority(prio);
        t.setPomoEstimate(0); t.setPomoCount(0); t.setNote(""); t.setBatchKey(batchKey);
        t.setStartDate(date.toString()); t.setEndDate(date.toString());
        t.setDoneDate("done".equals(status) ? date.toString() : null);
        t.setCreatedAt(date.toString());
        todos.save(t);
    }

    private void pomo(String id, LocalDate date, String title) {
        Pomodoro p = new Pomodoro();
        p.setId(id); p.setDate(date.toString()); p.setDuration(25);
        p.setStartedAt(date + "T09:00:00Z"); p.setEndedAt(date + "T09:25:00Z");
        p.setTitle(title); p.setCreatedAt(date.toString());
        pomodoros.save(p);
        WorkLog w = new WorkLog();
        w.setId("wl_" + id); w.setDate(date.toString()); w.setMinutes(25); w.setSource("pomodoro"); w.setNote(title);
        w.setCreatedAt(date.toString());
        workLogs.save(w);
    }

    private void workLog(String id, LocalDate date, int min, String source, String note) {
        WorkLog w = new WorkLog();
        w.setId(id); w.setDate(date.toString()); w.setMinutes(min); w.setSource(source); w.setNote(note);
        w.setCreatedAt(date.toString());
        workLogs.save(w);
    }

    private void note(String id, String projectId, String title, String content, int daysAgo) {
        Note n = new Note();
        n.setId(id); n.setProjectId(projectId); n.setTitle(title); n.setContent(content);
        String iso = LocalDate.now().minusDays(daysAgo) + "T10:00:00.000Z";
        n.setPublishedAt(iso); n.setUpdatedAt(iso); n.setCreatedAt(iso);
        notes.save(n);
    }

    private void media(String id, String type, String title, String status, int progress, int startDaysAgo, Integer endDaysAgo, Integer rating, String note) {
        Media m = new Media();
        m.setId(id); m.setType(type); m.setTitle(title); m.setStatus(status); m.setProgress(progress);
        m.setStartDate(startDaysAgo > 0 ? LocalDate.now().minusDays(startDaysAgo).toString() : "");
        m.setEndDate(endDaysAgo != null ? LocalDate.now().minusDays(endDaysAgo).toString() : "");
        m.setRating(rating); m.setNote(note);
        m.setCreatedAt(LocalDate.now().toString());
        media.save(m);
    }
}
