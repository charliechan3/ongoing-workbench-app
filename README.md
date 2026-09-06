# 进行时-个人工作台

面向个人的目标、项目、任务、知识与产出一体化工作台。前端 Vue 3，后端 Spring Boot。

## 架构

```
ongoing-workbench-app/
├── backend/                          # Spring Boot 3.3.4 + Java 21 + JPA + H2
│   ├── src/main/java/com/ongoing/workbench/
│   │   ├── entity/                   # 14 个实体（Area→Goal→Project→Task→Action 等）
│   │   ├── repo/                     # JPA Repository
│   │   ├── controller/               # REST API（含备份导入导出）
│   │   └── service/BackupService.java # 备份导出/预览/覆盖/合并恢复
│   └── src/main/resources/application.yml
└── frontend/                         # Vite 5 + Vue 3 + Pinia + vue-router + axios
    └── src/
        ├── api/index.js              # axios 封装
        ├── stores/data.js            # 数据 Store + 统计引擎
        ├── components/               # 通用组件
        └── views/                    # 六个视图
            ├── Dashboard.vue         # 首页仪表盘（含番茄钟）
            ├── TodoView.vue          # 待办（列表/日历/批量创建/升级）
            ├── CareerView.vue        # 职业发展（项目/笔记/能力六边形）
            ├── MediaView.vue         # 图书影视
            ├── StatsView.vue         # 数据统计（全部来自真实操作记录）
            └── SettingsView.vue      # 备份恢复/偏好
```

## 启动方式

### 1. 启动后端（端口 8080）

```bash
# 需要 JDK 21+
cd ongoing-workbench-app/backend
java -jar target/ongoing-workbench-1.0.0.jar --server.port=8080
```

首次启动自动初始化种子数据（1 区域 / 2 目标 / 6 项目 / 4 任务 / 7 行动 / 13 待办 / 番茄与工时记录 / 3 笔记 / 5 影视 / 1 版本）。

数据库文件：`backend/data/workbench.mv.db`（H2 文件库，删除即重置）。

### 2. 启动前端（端口 5173）

```bash
cd ongoing-workbench-app/frontend
npm install
npm run dev
```

访问 http://localhost:5173（`/api` 已配置代理到 8080）。

### 重新编译后端

```bash
cd ongoing-workbench-app/backend
mvn -DskipTests package
```

## 数据存储模式（APP_MODE）

数据访问层是 DB 无关的（Spring Data JPA），切换存储只需切换 Spring Profile，由环境变量 `APP_MODE` 控制（默认 `local`）：

| 模式 | 启动方式 | 存储 |
|------|---------|------|
| `local`（默认） | `java -jar target/ongoing-workbench-1.0.0.jar --server.port=8080` | 本地 H2 文件库 `backend/data/workbench.mv.db`，开放 `/h2-console` |
| `cloud` | 设置环境变量后同样方式启动 | 云端 MySQL 兼容数据库（多端同步） |

cloud 模式所需环境变量（缺任一项启动即报错终止，提示明确）：

```bash
set APP_MODE=cloud
set CLOUD_DB_URL=jdbc:mysql://<host>:3306/ongoing?useSSL=true^&serverTimezone=GMT+8^&characterEncoding=utf8
set CLOUD_DB_USER=<用户名>
set CLOUD_DB_PASS=<密码>
set APP_JWT_SECRET=<32位以上随机字符串，建议覆盖>
java -jar target/ongoing-workbench-1.0.0.jar --server.port=8080
```

- 云库可以从空库起步，`ddl-auto: update` 会按实体自动建表；也可以先用设置页的备份导出 JSON → 在云库模式下恢复导入，实现本地数据上云。
- **Supabase（PostgreSQL，当前选定方案）**：连接串用 Session pooler（`jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require`，直接连接是 IPv6-only），用户名带项目前缀 `postgres.<ref>`；驱动按 URL 自动推断，无需 CLOUD_DB_DRIVER。
- **MySQL 兼容库**（TiDB Cloud 等）：驱动 `com.mysql.cj.jdbc.Driver`；若用其它数据库，用 `CLOUD_DB_DRIVER` 覆盖并确认 pom 里有对应驱动（已内置 mysql + postgresql 两个驱动）。

## 核心交互

| 功能 | 入口 |
|------|------|
| 快速创建待办 | 首页顶部输入框 / 任意页面 `Ctrl+K` 命令面板 |
| 番茄钟 | 首页右侧卡片，完成自动沉淀统计 |
| 待办批量创建 | Todo 页「⧉ 批量创建」（每日/每周/每月/区间） |
| 待办升级为项目/行动 | Todo 条目 ↗ 按钮 |
| 能力六边形 | 职业发展 → 能力 Tab |
| 数据备份/恢复 | 设置 → 备份与恢复（覆盖/合并两种模式） |

## 设计原则

- **减少记录成本**：番茄钟、完成待办、行动自动沉淀统计数据，无需手动记账。
- **统计源于真实操作**：所有数字由操作记录实时计算，无虚报。
- **界面风格**：紫色主色调、圆角卡片、轻阴影、大量留白（Linear + Notion 风格）。
