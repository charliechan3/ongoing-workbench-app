import { defineStore } from 'pinia'
import { api } from '../api'

const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
// 番茄钟会话的 localStorage 持久化键（跨刷新恢复进行中的专注）
const POMO_KEY = 'ongoing.pomoSession'
// 本地时区日期，避免 toISOString(UTC) 在凌晨取错"今天"
export const fmtDate = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
export const today = () => fmtDate(new Date())
const now = () => new Date().toISOString()

/* ============ 优先级归一化（2026-09-06）============
   早期种子数据用 high/medium/low，新数据用 P1/P2/P3，两套口径曾混存。
   统一为 P1/P2/P3：加载时兜底归一，写入前也归一，防止历史值再混入。 */
export const normPriority = (p) => {
  const map = { high: 'P1', '高': 'P1', medium: 'P2', mid: 'P2', '中': 'P2', low: 'P3', '低': 'P3', '普通': 'P3' }
  const v = String(p || '').trim()
  if (map[v]) return map[v]
  return ['P1', 'P2', 'P3'].includes(v) ? v : 'P3'
}
const normPriIn = (list) => (list || []).forEach(x => { if (x) x.priority = normPriority(x.priority) })

/* ============ 项目排序（2026-09-13）============
   项目列表统一排序口径：
   1) 已完成的（status === 'done'）放最后，避免视线被沉底项占满
   2) 优先级从高到低：P1 > P2 > P3（P1 在前）
   3) 同优先级内按「起止日期」倒序：endDate 优先，其次 startDate，再没就 createdAt
      字典序即可比（YYYY-MM-DD 的 ISO 格式天然有序），晚的在前
   4) 真有完全相同的兜底值，按 createdAt 升序保证稳定 */
export const priOrd = (p) => p === 'P1' ? 0 : p === 'P2' ? 1 : 2
export function sortProjectCards(list) {
  return [...list].sort((a, b) => {
    const ad = a.status === 'done', bd = b.status === 'done'
    if (ad !== bd) return ad ? 1 : -1
    const dp = priOrd(a.priority) - priOrd(b.priority)
    if (dp !== 0) return dp
    const da = a.endDate || a.startDate || a.createdAt || ''
    const db = b.endDate || b.startDate || b.createdAt || ''
    if (da !== db) return da < db ? 1 : -1
    return String(a.createdAt || '').localeCompare(String(b.createdAt || ''))
  })
}

/* ============ 游客模式种子数据 ============
   游客令牌无账号行、后端返回空数据；这里在 loadAll 后内存注入两个示例领域，
   让游客一进来就有内容可探索。仅存内存（刷新即失效，写请求也不落库），不与真实数据互染。 */
const isGuest = () => {
  try { return JSON.parse(localStorage.getItem('ongoing.user') || 'null')?.id === 'guest' } catch { return false }
}
const GUEST_SEED_AREAS = [
  { id: 'guest-area-work', name: '工作项目', note: '示例领域：试着在这里建立 区域 → 项目 → 任务 → 行动', sort: 0 },
  { id: 'guest-area-study', name: '学习计划', note: '示例领域：数据仅本次体验有效，注册登录后才能保存你自己的规划', sort: 1 }
]

/* ============ 重复行动工具 ============ */
const parseYmd = (f) => { const [y, m, d] = String(f).split('-').map(Number); return new Date(y, m - 1, d) }
// 行动 repeat 规则是否命中某日 f（无 repeat / 无类型 → false）
export function repeatHits(action, f) {
  const r = action && action.repeat
  if (!r || !r.type) return false
  const d = parseYmd(f)
  if (isNaN(d.getTime())) return false
  if (r.type === 'daily') return true
  if (r.type === 'weekly') {
    const wds = Array.isArray(r.weekdays) && r.weekdays.length ? r.weekdays.map(Number) : []
    return wds.length ? wds.includes(d.getDay()) : true
  }
  if (r.type === 'monthly') {
    const days = Array.isArray(r.days) && r.days.length ? r.days.map(Number) : []
    return days.length ? days.includes(d.getDate()) : true
  }
  return false
}
// 重复行动在日期 f 的实例是否已完成（以 action.doneDates 为准）
export function repeatDoneOn(action, f) {
  return !!(action && action.repeat && action.repeat.type && (action.doneDates || []).includes(f))
}
// 行动是否是重复型
export const isRepeat = (action) => !!(action && action.repeat && action.repeat.type)
// todo 是否出现在某日的"列表"中（列表口径，与日历口径一致）：
// 重复行动按规则命中且处于生效区间；普通 todo 锚定其 date
export function todoHitsDay(todo, action, f) {
  if (isRepeat(action)) {
    const inEffect = (!todo.startDate || todo.startDate <= f) && (!todo.endDate || todo.endDate >= f)
    return repeatHits(action, f) && inEffect
  }
  return todo.date === f
}
// 两条重复规则是否等价（忽略键序，按 type/weekdays/days 值比较）
function sameRepeat(a, b) {
  if (!a && !b) return true
  if (!a || !b) return false
  const norm = (r) => r && r.type ? JSON.stringify({ type: r.type, weekdays: [...(r.weekdays || [])].sort((x, y) => x - y), days: [...(r.days || [])].sort((x, y) => x - y) }) : ''
  return norm(a) === norm(b)
}

/* ============ 子项（行动/待办的清单拆分）============
   子项只有 名字 / 完成状态 / 排序 三个字段，归属字段 actionId 与 todoId 二选一。
   因为 Todo 是 Action 的投影，同一逻辑对象在两侧都会出现，所以：
   - 读取：actionId 与 todoId 任意命中即算属于它（投影两侧看到同一份子项）
   - 写入：优先落在 action 侧（有绑定行动时），否则落在 todo 侧
   排序按 sort 升序，兜底 createdAt（无 sort 的历史数据也能稳定展示） */
export function checklistItemsOf(items, parent = {}) {
  const actionId = parent.actionId || ''
  const todoId = parent.todoId || ''
  return (items || [])
    .filter(c => c && ((actionId && c.actionId === actionId) || (todoId && c.todoId === todoId)))
    .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0) || String(a.createdAt || '').localeCompare(String(b.createdAt || '')))
}
// 子项写入的归属（二选一，行动优先）
export const checklistOwnerOf = (parent = {}) => {
  const actionId = parent.actionId || ''
  return { actionId: actionId || null, todoId: actionId ? null : (parent.todoId || null) }
}

/* ============ 统计 & 计算引擎 ============ */

// 任务进度：由子行动加权平均（复用后端逻辑）
export function taskProgress(task, actions) {
  if (task.progress != null && task.progress > 0) return task.progress
  const subs = actions.filter(a => a.taskId === task.id)
  if (!subs.length) return task.status === 'done' ? 100 : 0
  const sum = subs.reduce((s, a) => s + (a.status === 'done' ? 100 : 0), 0)
  return Math.round(sum / subs.length)
}

// 项目进度：任务 + 独立行动加权平均
export function projectProgress(project, tasks, actions) {
  const t = tasks.filter(x => x.projectId === project.id).map(x => taskProgress(x, actions))
  const a = actions.filter(x => !x.taskId && x.projectId === project.id).map(x => x.status === 'done' ? 100 : 0)
  const all = [...t, ...a]
  if (!all.length) return project.status === 'done' ? 100 : 0
  return Math.round(all.reduce((s, v) => s + v, 0) / all.length)
}

// 行动计划：由 repeat 规则计算 next 日期
export function nextDueDate(repeat, from) {
  const base = from ? new Date(from) : new Date()
  if (!repeat || !repeat.type) return null
  const t = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate())
  const fmt = (d) => fmtDate(d) // 本地时区格式化（toISOString 会把 GMT+8 的本地午夜错位到前一天）
  if (repeat.type === 'daily') { const d = t(base); d.setDate(d.getDate() + 1); return fmt(d) }
  if (repeat.type === 'weekly') {
    const wd = Array.isArray(repeat.weekdays) && repeat.weekdays.length ? repeat.weekdays.map(Number) : [base.getDay()]
    for (let i = 1; i <= 7; i++) {
      const d = t(base); d.setDate(d.getDate() + i)
      if (wd.includes(d.getDay())) return fmt(d)
    }
  }
  if (repeat.type === 'monthly') {
    const days = Array.isArray(repeat.days) && repeat.days.length ? repeat.days.map(Number) : [base.getDate()]
    for (let m = 1; m <= 2; m++) {
      const d = new Date(base.getFullYear(), base.getMonth() + m, 1)
      const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
      for (const day of days) {
        const dd = new Date(d.getFullYear(), d.getMonth(), Math.min(day, last))
        if (dd > t(base)) return fmt(dd)
      }
    }
  }
  return null
}

// 批量生成任务日期
export function batchDates(rule, ref) {
  const start = rule.startDate ? new Date(rule.startDate) : new Date(ref || today())
  const fmt = (d) => fmtDate(d) // 本地时区格式化
  const out = []
  const mode = rule.mode || 'daily'
  if (mode === 'daily') {
    for (let i = 0; i < (rule.count || 7); i++) { const d = new Date(start); d.setDate(d.getDate() + i); out.push(fmt(d)) }
  } else if (mode === 'weekly') {
    const wds = (rule.weekdays || [1, 3, 5]).map(Number)
    let d = new Date(start)
    let made = 0
    while (made < (rule.count || 12)) {
      if (wds.includes(d.getDay())) { out.push(fmt(d)); made++ }
      d.setDate(d.getDate() + 1)
    }
  } else if (mode === 'monthly') {
    const days = (rule.days || [15]).map(Number)
    for (let m = 0; m < (rule.count || 3); m++) {
      const d = new Date(start.getFullYear(), start.getMonth() + m, 1)
      const last = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
      for (const day of days) out.push(fmt(new Date(d.getFullYear(), d.getMonth(), Math.min(day, last))))
    }
  } else if (mode === 'range') {
    const from = new Date(rule.startDate || ref), to = new Date(rule.endDate || ref)
    for (let d = new Date(from); d <= to; d.setDate(d.getDate() + 1)) out.push(fmt(new Date(d)))
  }
  return out
}

/* ============ 主 Store ============ */

export const useDataStore = defineStore('data', {
  state: () => ({
    loaded: false,
    loading: false,
    areas: [], projects: [], tasks: [], actions: [],
    todos: [], pomodoros: [], worklogs: [], notes: [], medias: [],
    checklist: [],
    settings: {},    toasts: [],    // 首页番茄钟会话。放在 store 是为了跨页面保持（组件卸载不影响倒计时）。
    // endAt = 结束时间戳(ms)；倒计时始终由 endAt - Date.now() 推导，不靠累加计数。
    // 会话额外持久化到 localStorage（POMO_KEY），刷新页面后由 restorePomoSession() 恢复。
    pomoSession: { endAt: null, minutes: 25, todoId: '' }
  }),

  getters: {
    // 番茄时长（分钟）。后端字段为 pomodoroMin，历史上前端误读为 pomoMinutes，
    // 这里兼容两种写法并兜底 25，避免设置项改了不生效。
    pomoMinutes: s => {
      const raw = s.settings?.pomodoroMin ?? s.settings?.pomoMinutes
      const n = Number(raw)
      return Number.isFinite(n) && n >= 1 ? Math.round(n) : 25
    },

    projectMap: s => Object.fromEntries(s.projects.map(p => [p.id, p])),
    taskMap: s => Object.fromEntries(s.tasks.map(t => [t.id, t])),
    areaMap: s => Object.fromEntries(s.areas.map(a => [a.id, a])),
    mediaMap: s => Object.fromEntries(s.medias.map(m => [m.id, m])),

    openTodos: s => s.todos.filter(t => t.status !== 'done').sort((a, b) => (a.priority || 'P3').localeCompare(b.priority || 'P3')),
    doneTodos: s => s.todos.filter(t => t.status === 'done'),
    todayTodos: s => s.todos.filter(t => (t.date === today() && t.status !== 'done')),

    // 「今天」待办统一口径（与 TodoView 列表"今天"分组一致）：
    // 重复行动命中今天且在生效区间 → 出现（完成态看 doneDates）；普通 todo date===今天 → 出现（含已完成的，沉底）
    todayTodoRows: (s) => {
      const t = today()
      const actMap = Object.fromEntries(s.actions.map(a => [a.id, a]))
      const rows = s.todos
        .filter(td => todoHitsDay(td, td.actionId ? actMap[td.actionId] : null, t))
        .map(td => {
          const a = td.actionId ? actMap[td.actionId] : null
          return {
            todo: td,
            done: isRepeat(a) ? repeatDoneOn(a, t) : td.status === 'done',
            repeat: isRepeat(a)
          }
        })
      rows.sort((x, y) => x.done - y.done || (x.todo.priority || 'P3').localeCompare(y.todo.priority || 'P3'))
      return rows
    },

    todayPomos: s => s.pomodoros.filter(p => p.date === today()).length,
    todayMinutes: s => s.pomodoros.filter(p => p.date === today()).reduce((n, p) => n + (p.minutes || 25), 0),
    todayWorkMinutes: s => s.worklogs.filter(w => w.date === today()).reduce((n, w) => n + (w.minutes || 0), 0),

    streak: (s) => {
      const set = new Set(s.pomodoros.map(p => p.date))
      let n = 0
      const d = new Date()
      while (true) {
        const f = fmtDate(d)
        if (set.has(f)) { n++; d.setDate(d.getDate() - 1) } else break
      }
      return n
    },

    // 项目汇总卡
    projectCards(s) {
      const tMap = {}
      for (const t of s.tasks) (tMap[t.projectId] = tMap[t.projectId] || []).push(t)
      const aMap = {}
      for (const a of s.actions) (aMap[a.projectId] = aMap[a.projectId] || []).push(a)
      return s.projects.map(p => {
        const subs = [...(tMap[p.id] || []), ...(aMap[p.id] || []).filter(a => !a.taskId)]
        const done = subs.filter(x => x.status === 'done').length
        return { ...p, progress: projectProgress(p, tMap[p.id] || [], s.actions), doneCount: done, totalCount: subs.length }
      })
    },

    // 7 日行动统计
    last7() {
      const days = []
      for (let i = 6; i >= 0; i--) {
        const d = new Date(); d.setDate(d.getDate() - i)
        const f = fmtDate(d)
        days.push(f)
      }
      return days
    }
  },

  actions: {
    toast(msg, type = 'ok') {
      const id = uid()
      this.toasts.push({ id, msg, type })
      setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id) }, 2600)
    },

    async loadAll() {
      this.loading = true
      try {
        const [areas, projects, tasks, actions, todos, pomodoros, worklogs, notes, medias, settings] =
          await Promise.all([
            api.areas.list(), api.projects.list(), api.tasks.list(), api.actions.list(),
            api.todos.list(), api.pomodoros.list(), api.worklogs.list(), api.notes.list(), api.medias.list(),
            api.settings.get()
          ])
        this.areas = areas; this.projects = projects; this.tasks = tasks; this.actions = actions
        this.todos = todos; this.pomodoros = pomodoros; this.worklogs = worklogs; this.notes = notes; this.medias = medias
        // 子项单独取：老版本后端没有 /api/checklist 时（404）降级为空数组，不拖垮其余数据加载
        try { this.checklist = await api.checklist.list() || [] } catch (e) { this.checklist = [] }
        // 老 Pomodoro 没 minutes 字段（实体 2026-09-12 才加），回填到 duration 让 StatsView 立刻显示真实时长
        for (const p of this.pomodoros) if (p.minutes == null) p.minutes = p.duration || 25
        // 优先级统一为 P1/P2/P3（兼容早期种子数据 high/medium/low，内存归一，展示/排序口径一致）
        normPriIn(this.projects); normPriIn(this.tasks); normPriIn(this.actions); normPriIn(this.todos)
        this.settings = settings || {}
        this.loaded = true
        // 游客模式：注入示例领域（仅内存，注册登录后自动消失）
        if (isGuest() && !this.areas.length) {
          this.areas = GUEST_SEED_AREAS.map(a => ({ ...a, createdAt: now() }))
        }
        // 历史行动补齐投影 todo（幂等：仅补缺失的 actionId 绑定条目）
        try { await this.ensureActionTodos() } catch (e) { console.warn('sync actions→todos failed', e) }
      } catch (e) {
        this.toast('无法连接后端服务，请确认已启动（端口 8080）', 'err')
        console.error(e)
      } finally { this.loading = false }
    },

    /* 退出登录/切号时清空本地数据（下次登录 loadAll 重新拉取） */
    resetAll() {
      this.loaded = false
      this.loading = false
      this.areas = []; this.projects = []; this.tasks = []; this.actions = []
      this.todos = []; this.pomodoros = []; this.worklogs = []; this.notes = []; this.medias = []
      this.checklist = []
      this.settings = {}
      this.toasts = []
      this.stopPomoSession()
    },

    /* ---- 通用 CRUD 包装 ---- */
    async create(res, data, opts = {}) {
      const item = { id: uid(), createdAt: now(), ...data }
      if ('priority' in item && ['projects', 'tasks', 'actions', 'todos'].includes(res)) item.priority = normPriority(item.priority)
      const saved = await api[res].create(item)
      this[res].unshift(saved || item)
      if (!opts.silent) this.toast(opts.msg || '已创建')
      return saved || item
    },
    async update(res, id, patch, opts = {}) {
      const cur = this[res].find(x => x.id === id) || {}
      const body = { ...cur, ...patch }
      if ('priority' in body && ['projects', 'tasks', 'actions', 'todos'].includes(res)) body.priority = normPriority(body.priority)
      const saved = await api[res].update(id, body)
      const i = this[res].findIndex(x => x.id === id)
      if (i >= 0) this[res].splice(i, 1, saved)
      if (!opts.silent) this.toast(opts.msg || '已保存')
      return saved
    },
    async remove(res, id, opts = {}) {
      await api[res].remove(id)
      this[res] = this[res].filter(x => x.id !== id)
      if (!opts.silent) this.toast(opts.msg || '已删除')
    },

    /* ============ 子项（清单拆分：行动 / 待办 通用）============
       子项只有名字 + 完成状态 + 排序，不含番茄/优先级/日期，交互只有
       勾选完成、双击改名、子列表内拖动排序、删除。
       parent 形如 { actionId, todoId }：读取两者取并集，写入走 checklistOwnerOf（行动优先）。 */
    _checklistOf(parent) {
      return checklistItemsOf(this.checklist, parent)
    },

    /* 新增子项：sort 取同级末尾（最大值 +1），追加在子列表最后 */
    async addChecklistItem(parent, name, opts = {}) {
      const text = String(name == null ? '' : name).trim()
      if (!text) return null
      const sort = this._checklistOf(parent).reduce((m, c) => Math.max(m, (c.sort ?? 0) + 1), 0)
      return this.create('checklist', { ...checklistOwnerOf(parent), name: text, done: false, sort }, { silent: true, ...opts })
    },

    /* 勾选/取消完成（轻操作，不弹提示；父级行动/待办不自动联动完成） */
    async toggleChecklist(item) {
      if (!item) return
      await this.update('checklist', item.id, { done: !item.done }, { silent: true })
    },

    async renameChecklistItem(item, name, opts = {}) {
      const text = String(name == null ? '' : name).trim()
      if (!item || !text || text === item.name) return
      await this.update('checklist', item.id, { name: text }, { silent: true })
      if (!opts.silent) this.toast(opts.msg || '已重命名')
    },

    async removeChecklistItem(item, opts = {}) {
      if (!item) return
      await this.remove('checklist', item.id, { silent: true })
      if (!opts.silent) this.toast(opts.msg || '已删除')
    },

    /* 拖动排序：按新顺序整表重编号（sort = 下标），只落库真正变化的项。
       不用"取两端中间值"——sort 是整数列，没有插值空间，反复拖动会退化。 */
    async reorderChecklist(list) {
      const jobs = []
      ;(list || []).forEach((it, i) => {
        if (!it || (it.sort ?? 0) === i) return
        jobs.push(this.update('checklist', it.id, { sort: i }, { silent: true }))
      })
      if (jobs.length) await Promise.all(jobs)
    },

    /* 父级被删除时清理其子项（否则会留下不可见、也永远删不掉的孤儿数据） */
    async _purgeChecklist({ actionId, todoId } = {}) {
      const hit = this.checklist.filter(c =>
        (actionId && c.actionId === actionId) || (todoId && c.todoId === todoId))
      if (!hit.length) return
      const ids = new Set(hit.map(c => c.id))
      for (const id of ids) { try { await api.checklist.remove(id) } catch (e) { /* 清理失败不阻塞父级删除 */ } }
      this.checklist = this.checklist.filter(c => !ids.has(c.id))
    },

    /* ============ Todo ↔ Action 双向同步（Todo = 职业发展 Action 的待办投影） ============ */
    // 从 action 字段生成一条投影 todo 的初始数据
    // date 允许为 null —— 表示行动未填开始日期，对应 todo 进 todo 列表的「未分配」分组（行动没排期就不该硬塞今天）
    _todoFromAction(action) {
      return {
        actionId: action.id,
        text: action.name || '',
        date: action.startDate || null,
        status: action.status === 'done' ? 'done' : 'todo',
        doneDate: action.status === 'done' ? today() : null,
        priority: action.priority || 'P3',
        projectId: action.projectId || null,
        taskId: action.taskId || null,
        note: action.note || action.desc || '',
        completionNote: action.completionNote || '',
        startDate: action.startDate || null,
        endDate: action.endDate || null,
        pomoCount: action.pomoCount || 0,
        pomoEstimate: action.pomoEstimate || 0
      }
    },

    // 只落库一条 action（不投影 todo），供内部复用
    async _createActionEntity(data) {
      const item = { id: uid(), createdAt: now(), status: 'todo', priority: 'P3', pomoCount: 0, sort: 0, ...data }
      const saved = await api.actions.create(item)
      this.actions.unshift(saved || item)
      return saved || item
    },

    // 确保 action 存在其投影 todo（幂等：已有则返回，否则新建）
    async syncActionToTodo(action) {
      let todo = this.todos.find(t => t.actionId === action.id)
      if (!todo) {
        const t0 = { id: uid(), createdAt: now(), ...this._todoFromAction(action) }
        const td = await api.todos.create(t0)
        this.todos.unshift(td || t0)
        todo = td || t0
      }
      return todo
    },

    // 幂等补齐：所有行动都应有投影 todo
    async ensureActionTodos() {
      for (const a of this.actions) {
        const ex = this.todos.some(t => t.actionId === a.id)
        if (!ex) await this.syncActionToTodo(a)
      }
    },

    // 创建行动（职业发展任意位置）→ 自动同步一条 Todo
    async addAction(payload, opts = {}) {
      const act = await this._createActionEntity(payload)
      await this.syncActionToTodo(act)
      if (!opts.silent) this.toast(opts.msg || '已创建行动，并同步到待办')
      return act
    },

    /* 编辑行动 → 名称/描述/状态/日期同步到投影 todo */
    async updateAction(id, patch, opts = {}) {
      const cur = this.actions.find(x => x.id === id) || {}
      const saved = await api.actions.update(id, { ...cur, ...patch })
      const i = this.actions.findIndex(x => x.id === id)
      if (i >= 0) this.actions.splice(i, 1, saved)
      const todo = this.todos.find(t => t.actionId === id)
      if (todo) {
        const tp = {}
        if ('name' in patch) tp.text = patch.name
        if ('desc' in patch) tp.note = patch.desc || ''
        if ('completionNote' in patch) tp.completionNote = patch.completionNote || ''
        if ('pomoCount' in patch) tp.pomoCount = patch.pomoCount || 0
        if ('pomoEstimate' in patch) tp.pomoEstimate = patch.pomoEstimate || 0
        if ('priority' in patch) tp.priority = patch.priority
        if ('startDate' in patch) { tp.startDate = patch.startDate || null; if (patch.startDate) tp.date = patch.startDate }
        if ('endDate' in patch) tp.endDate = patch.endDate || null
        // 重复行动的"完成"以每日实例(doneDates)表达，不允许整条置完成
        if ('status' in patch && !isRepeat(saved)) {
          tp.status = patch.status === 'done' ? 'done' : 'todo'
          tp.doneDate = patch.status === 'done' ? today() : null
        }
        if (Object.keys(tp).length) await this.update('todos', todo.id, tp, { silent: true })
      }
      if (!opts.silent) this.toast(opts.msg || '已保存')
      return saved
    },

    /* 删除行动 → 连带删除其投影 todo */
    async removeAction(id, opts = {}) {
      const act = this.actions.find(x => x.id === id)
      const todo = act ? this.todos.find(t => t.actionId === act.id) : null
      await api.actions.remove(id)
      this.actions = this.actions.filter(x => x.id !== id)
      if (todo) {
        await api.todos.remove(todo.id)
        this.todos = this.todos.filter(x => x.id !== todo.id)
      }
      await this._purgeChecklist({ actionId: id, todoId: todo ? todo.id : null })
      if (!opts.silent) this.toast(opts.msg || '已删除行动及同步待办')
    },

    /* 创建 Todo（统一入口）。
       未绑定 → 纯待办；
       绑定了任务/项目 → 在职业发展中同步生成对应行动并回填 actionId（互为投影） */
    async addTodo(payload, opts = {}) {
      const item = { id: uid(), createdAt: now(), status: 'todo', priority: 'P3', pomoCount: 0, pomoEstimate: 0, ...payload }
      const saved = await api.todos.create(item)
      this.todos.unshift(saved || item)
      const todo = saved || item
      const task = todo.taskId ? this.taskMap[todo.taskId] : null
      const project = (!task && todo.projectId) ? this.projectMap[todo.projectId] : null
      if (task || project) {
        const act = await this._createActionEntity({
          name: todo.text || '未命名',
          desc: todo.note || '',
          taskId: task ? task.id : null,
          projectId: task ? task.projectId : project.id,
          areaId: task ? task.areaId : project.areaId,
          status: todo.status,
          priority: todo.priority,
          startDate: todo.date || null,
          endDate: todo.endDate || null,
          pomoCount: todo.pomoCount || 0
        })
        await this.update('todos', todo.id, { actionId: act.id }, { silent: true })
      }
      if (!opts.silent) this.toast(opts.msg || '已创建')
      return todo
    },

    /* 创建重复待办：一条带 repeat 规则的行动 + 投影 todo（日历按规则展开、完成按天记录） */
    async addRepeatTodo(payload, opts = {}) {
      const task = payload.taskId ? this.taskMap[payload.taskId] : null
      const project = (!task && payload.projectId) ? this.projectMap[payload.projectId] : null
      const act = await this._createActionEntity({
        name: payload.text || '未命名',
        desc: payload.note || '',
        taskId: task ? task.id : null,
        projectId: task ? task.projectId : (project ? project.id : null),
        areaId: task ? task.areaId : (project ? project.areaId : null),
        status: 'todo',
        priority: payload.priority || 'P3',
        startDate: payload.startDate || today(),
        endDate: payload.endDate || null,
        repeat: payload.repeat || null,
        doneDates: []
      })
      await this.syncActionToTodo(act)
      if (!opts.silent) this.toast('已创建重复待办')
      return act
    },

    /* 保存 Todo 详情（含重复规则设置/修改/清除，详情弹窗用）：
       - 已绑定行动 → 名称/备注/日期/重复规则写回行动，行动再同步投影 todo
       - 纯待办首次设重复 → 自动补建一条独立行动（承载 repeat + doneDates）并绑定 actionId
       规则永远落在行动侧（action.repeat + doneDates），todo 只当投影 */
    async saveTodoDetail(todo, repeat, opts = {}) {
      const saved = await this.update('todos', todo.id, todo, { silent: true })
      const hasRep = !!(repeat && repeat.type)
      let act = todo.actionId ? this.actions.find(a => a.id === todo.actionId) : null
      if (!act && hasRep) {
        const task = todo.taskId ? this.taskMap[todo.taskId] : null
        const project = (!task && todo.projectId) ? this.projectMap[todo.projectId] : null
        act = await this._createActionEntity({
          name: todo.text || '未命名',
          desc: todo.note || '',
          taskId: task ? task.id : null,
          projectId: task ? task.projectId : (project ? project.id : null),
          areaId: task ? task.areaId : (project ? project.areaId : null),
          status: 'todo',
          priority: todo.priority || 'P3',
          startDate: todo.date || today(),
          endDate: todo.endDate || null,
          repeat,
          doneDates: [],
          completionNote: todo.completionNote || '',
          pomoCount: todo.pomoCount || 0
        })
        await this.update('todos', todo.id, { actionId: act.id }, { silent: true })
      } else if (act) {
        const ap = {}
        if (act.name !== todo.text) ap.name = todo.text
        if (act.priority !== todo.priority) ap.priority = todo.priority
        if ((act.note || act.desc || '') !== (todo.note || '')) ap.desc = todo.note || ''
        if ((act.completionNote || '') !== (todo.completionNote || '')) ap.completionNote = todo.completionNote || ''
        if ((act.pomoEstimate || 0) !== (todo.pomoEstimate || 0)) ap.pomoEstimate = todo.pomoEstimate || 0
        if ((act.startDate || null) !== (todo.date || null)) ap.startDate = todo.date || null
        if ((act.endDate || '') !== (todo.endDate || '')) ap.endDate = todo.endDate || null
        if (hasRep !== isRepeat(act)) {
          ap.repeat = hasRep ? repeat : null
          if (hasRep) ap.doneDates = act.doneDates || []
          else { ap.doneDates = []; ap.status = 'todo' } // 取消重复：清空按日记录并回到未完成
        } else if (hasRep && !sameRepeat(act.repeat, repeat)) {
          ap.repeat = repeat
        }
        if (Object.keys(ap).length) await this.updateAction(act.id, ap, { silent: true })
      }
      if (!opts.silent) this.toast(opts.msg || '已保存')
      return saved
    },

    /* 列表内联改名：只动名字，其余字段一律不碰（对齐"双击改名"这种轻操作）。
       已绑定行动时同步回行动 name（updateAction 会再把名字写回投影 todo，互为投影不脱钩）。 */
    async renameTodo(todo, text, opts = {}) {
      const name = (text || '').trim()
      if (!name || name === todo.text) return
      await this.update('todos', todo.id, { text: name }, { silent: true })
      if (todo.actionId) {
        const act = this.actions.find(a => a.id === todo.actionId)
        if (act && act.name !== name) await this.updateAction(act.id, { name }, { silent: true })
      }
      if (!opts.silent) this.toast(opts.msg || '已重命名')
    },

    /* 填写完成情况（弹窗）：todo 与绑定行动双向同步；
       顺带可选标记完成（已完成则不改状态） */
    async saveCompletion(todo, text, opts = {}) {
      const completionNote = (text || '').trim()
      await this.update('todos', todo.id, { completionNote }, { silent: true })
      if (todo.actionId) {
        const act = this.actions.find(a => a.id === todo.actionId)
        if (act) await this.updateAction(act.id, { completionNote }, { silent: true })
      }
      if (opts.markDone && todo.status !== 'done') await this.toggleTodo({ ...todo, status: 'todo' })
      if (!opts.silent) this.toast(completionNote ? '已记录完成情况' : '已清空完成情况')
    },

    /* 删除 todo：带行动绑定的同时删除对应行动（互为投影） */
    async removeTodo(todo, opts = {}) {
      await api.todos.remove(todo.id)
      this.todos = this.todos.filter(x => x.id !== todo.id)
      if (todo.actionId) {
        const act = this.actions.find(a => a.id === todo.actionId)
        if (act) {
          await api.actions.remove(act.id)
          this.actions = this.actions.filter(x => x.id !== act.id)
        }
      }
      await this._purgeChecklist({ actionId: todo.actionId, todoId: todo.id })
      if (!opts.silent) this.toast(opts.msg || '已删除')
    },

    /* 勾选/取消 todo：
       绑定重复行动的 todo → 只切换"今天"实例（记入 doneDates，不整条置灰）；
       其余 → 状态 + doneDate 与绑定行动双向同步 */
    async toggleTodo(todo) {
      const act = todo.actionId ? this.actions.find(a => a.id === todo.actionId) : null
      if (isRepeat(act)) return this.toggleRepeat(act, today())
      const toDone = todo.status !== 'done'
      await this.update('todos', todo.id, { status: toDone ? 'done' : 'todo', doneDate: toDone ? today() : null }, { silent: true })
      if (act) {
        const dd = [...(act.doneDates || [])]
        if (toDone) { if (!dd.includes(today())) dd.push(today()) }
        else { const k = dd.indexOf(today()); if (k >= 0) dd.splice(k, 1) }
        await this.update('actions', act.id, { status: toDone ? 'done' : 'todo', doneDates: dd }, { silent: true })
      }
      if (toDone && this.settings.autoPomoOnTodo) {
        await this.create('pomodoros', { date: today(), minutes: this.pomoMinutes, todoId: todo.id, actionId: todo.actionId || null, text: todo.text, type: 'todo' }, { silent: true })
        const tp = { pomoCount: (todo.pomoCount || 0) + 1 }
        if (act) tp.pomoCount = Math.max((act.pomoCount || 0) + 1, tp.pomoCount)
        await this.update('todos', todo.id, { pomoCount: tp.pomoCount }, { silent: true })
        if (act) await this.update('actions', act.id, { pomoCount: (act.pomoCount || 0) + 1 }, { silent: true })
      }
    },

    // 重复行动"某日实例"切换完成：只把该日增/删到 doneDates；行动与 todo 的"今日态"随之同步
    async toggleRepeat(act, dayF) {
      const dd = [...(act.doneDates || [])]
      const i = dd.indexOf(dayF)
      if (i >= 0) dd.splice(i, 1); else dd.push(dayF)
      const doneToday = dd.includes(today())
      const status = doneToday ? 'done' : 'todo'
      await this.update('actions', act.id, { status, doneDates: dd }, { silent: true })
      const todo = this.todos.find(t => t.actionId === act.id)
      if (todo) await this.update('todos', todo.id, { status, doneDate: doneToday ? today() : null }, { silent: true })
      this.toast(i >= 0 ? '已取消当天完成' : '已完成当天 🎉')
      return dd
    },

    /* 拖拽改期：重复行动不可拖；带 startDate 的跨日 todo/行动整体平移（保持时长）；绑定行动的日期双向同步。
       todo.date 为空（行动创建时未填开始日期 → "未分配"组里的 todo）的特殊情况也支持：拖到任意一天后双向同步 */
    async moveTodoDate(todo, newDate, opts = {}) {
      if (!newDate) return false
      // 仅在原日期真有值时跳过"无变化"；todo.date 为空时不允许用 today() 兜底对比，否则会把"未分配→今天"的合法移动误判为不动
      if (todo.date && newDate === todo.date) return false
      const act = todo.actionId ? this.actions.find(a => a.id === todo.actionId) : null
      if (isRepeat(act)) { this.toast('重复行动按规则自动出现，不支持拖拽改期', 'err'); return false }
      const shift = (s, delta) => fmtDate(new Date(parseYmd(s).getTime() + delta))
      // todo 侧：单日改 date；跨日（有 startDate）整体平移
      const tp = { date: newDate }
      if (todo.startDate) {
        const delta = parseYmd(newDate) - parseYmd(todo.startDate)
        tp.startDate = newDate
        if (todo.endDate) tp.endDate = shift(todo.endDate, delta)
      }
      await this.update('todos', todo.id, tp, { silent: true })
      // 行动侧：同步 startDate（updateAction 会回写 todo 投影）；act.startDate 为空（"未分配"行动）也允许被设成 newDate，不去算"虚锚点"的平移
      if (act) {
        const delta = act.startDate ? parseYmd(newDate) - parseYmd(act.startDate) : 0
        const ap = { startDate: newDate }
        if (act.endDate) ap.endDate = shift(act.endDate, delta)
        await this.updateAction(act.id, ap, { silent: true })
      }
      if (!opts.silent) this.toast(`已移至 ${newDate}`)
      return true
    },

    /* 勾选/取消行动（职业发展 & 首页）：
       重复行动按"今天实例"切换；普通行动翻转 status/doneDates 并同步投影 todo */
    async completeAction(act, silent = false) {
      if (isRepeat(act)) {
        const dd = [...(act.doneDates || [])]
        const i = dd.indexOf(today())
        if (i >= 0) dd.splice(i, 1); else dd.push(today())
        const doneToday = dd.includes(today())
        const status = doneToday ? 'done' : 'todo'
        await this.update('actions', act.id, { status, doneDates: dd }, { silent: true })
        const todo = this.todos.find(t => t.actionId === act.id)
        if (todo) await this.update('todos', todo.id, { status, doneDate: doneToday ? today() : null }, { silent: true })
        if (!silent) this.toast(doneToday ? '已完成今天 ✓' : '已取消今天')
        return
      }
      const done = act.status === 'done'
      const dd = [...(act.doneDates || [])]
      const nxt = done
        ? { status: 'todo', doneDates: dd.filter(d => d !== today()) }
        : { status: 'done', doneDates: dd.includes(today()) ? dd : [...dd, today()] }
      await this.update('actions', act.id, nxt, { silent: true })
      const todo = this.todos.find(t => t.actionId === act.id)
      if (todo) {
        await this.update('todos', todo.id, { status: nxt.status, doneDate: nxt.status === 'done' ? today() : null }, { silent: true })
      }
      if (!done && this.settings.autoPomoOnAction && act.pomoCount == null) {
        await this.create('pomodoros', { date: today(), minutes: this.pomoMinutes, actionId: act.id, text: act.name, type: 'action' }, { silent: true })
      }
    },

    // 批量创建（repeat 规则）
    async batchCreateTasks(rule, dates, type = 'task') {
      for (const d of dates) {
        if (type === 'task') {
          await this.create('tasks', { projectId: rule.projectId, areaId: rule.areaId, name: rule.name, desc: rule.desc, status: 'todo', startDate: d, endDate: d, priority: rule.priority || 'P3' }, { silent: true })
        } else {
          await this.create('todos', { text: rule.name, date: d, status: 'todo', priority: rule.priority || 'P3', projectId: rule.projectId || null, taskId: rule.taskId || null, note: rule.desc || '' }, { silent: true })
        }
      }
      this.toast(`已批量创建 ${dates.length} 条`)
    },

    // 首页番茄钟会话（跨页面保持 + 刷新恢复）
    startPomoSession({ minutes, todoId } = {}) {
      const m = Number(minutes) > 0 ? Math.round(minutes) : this.pomoMinutes
      this.pomoSession = { endAt: Date.now() + m * 60000, minutes: m, todoId: todoId || '' }
      try { localStorage.setItem(POMO_KEY, JSON.stringify(this.pomoSession)) } catch {}
    },
    stopPomoSession() {
      this.pomoSession = { endAt: null, minutes: this.pomoMinutes, todoId: '' }
      try { localStorage.removeItem(POMO_KEY) } catch {}
    },

    // 结束当前番茄钟会话（到点或手动）：记录 + 若绑定待办则同步累计，并清理持久化。
    // 统一入口 —— 首页 / 待办页 / 刷新后补记都走这里，保证记录口径一致
    // （待办被删时降级为自由专注，而不是整颗丢弃）。
    async finishPomoSession() {
      const s = this.pomoSession
      if (!s.endAt) return
      const minutes = s.minutes || this.pomoMinutes
      let todoId = s.todoId || ''
      this.stopPomoSession() // 先清状态，避免重复触发
      let ref = {}
      if (todoId) {
        const t = this.todos.find(x => x.id === todoId)
        if (t) ref = { todoId, actionId: t.actionId || null, text: t.text, type: 'todo' }
        else todoId = '' // 待办在专注期间被删 → 降级为自由专注，不写悬空 todoId
      }
      await this.finishPomodoro(minutes, ref)
      this.toast(todoId ? `完成 1 个番茄钟（${minutes} 分钟），已计入所选待办 🍅` : `完成 1 个番茄钟（${minutes} 分钟），已记录 🍅`)
    },

    // 应用启动时恢复刷新前的会话（由 App.vue 在 loadAll 之后调用，
    // 保证查待办时 todos 已就绪）。过期超过 2 小时的会话直接丢弃，避免隔天补记出脏数据。
    restorePomoSession() {
      try {
        const raw = localStorage.getItem(POMO_KEY)
        if (!raw) return
        const s = JSON.parse(raw)
        localStorage.removeItem(POMO_KEY)
        if (!s || !s.endAt || !(s.minutes > 0)) return
        if (Date.now() - s.endAt > 2 * 3600 * 1000) return
        this.pomoSession = { endAt: s.endAt, minutes: s.minutes, todoId: s.todoId || '' }
        localStorage.setItem(POMO_KEY, JSON.stringify(this.pomoSession))
        if (Date.now() >= s.endAt) this.finishPomoSession() // 刷新期间到点 → 补记这一颗
      } catch { try { localStorage.removeItem(POMO_KEY) } catch {} }
    },

    // 完成番茄钟 → 记录 + 若绑定待办/行动则同步累计 pomoCount
    async finishPomodoro(minutes = 25, ref = {}) {
      const p = await this.create('pomodoros', { date: today(), minutes, ...ref }, { silent: true })
      if (ref.todoId) {
        const td = this.todos.find(x => x.id === ref.todoId)
        if (td) {
          await this.update('todos', td.id, { pomoCount: (td.pomoCount || 0) + 1 }, { silent: true })
          if (td.actionId) {
            const act = this.actions.find(a => a.id === td.actionId)
            if (act) await this.update('actions', act.id, { pomoCount: (act.pomoCount || 0) + 1 }, { silent: true })
          }
        }
      }
      return p
    },

    /* 手动校准行动的番茄完成数/估算总数（校准弹窗用）：
       - 仅改 action/todo 的属性字段，不写 pomodoros 记录 → 不影响统计页与首页今日番茄/今日工时
       - 完成数与估算总数均双写 action + 绑定 todo（维持 Todo↔Action 契约，
         2026-09-06 起后端 Action 也有 pomoEstimate 字段） */
    async calibrateActionPomo(act, { pomoCount, pomoEstimate } = {}, opts = {}) {
      const norm = (v) => Math.max(0, Math.round(Number(v) || 0))
      const ap = {}
      if (pomoCount != null) ap.pomoCount = norm(pomoCount)
      if (pomoEstimate != null) ap.pomoEstimate = norm(pomoEstimate)
      if (Object.keys(ap).length) await this.update('actions', act.id, ap, { silent: true })
      const todo = this.todos.find(t => t.actionId === act.id)
      if (todo) {
        const tp = {}
        if (pomoCount != null) tp.pomoCount = norm(pomoCount)
        if (pomoEstimate != null) tp.pomoEstimate = norm(pomoEstimate)
        if (Object.keys(tp).length) await this.update('todos', todo.id, tp, { silent: true })
      }
      if (!opts.silent) this.toast(opts.msg || '已校准番茄数')
    },

    // 记录工时
    async addWorklog(minutes, ref = {}) {
      return this.create('worklogs', { date: today(), minutes, ...ref }, { silent: true })
    }
  }
})

export { uid, now }

