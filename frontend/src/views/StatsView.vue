<script setup>
import { computed, ref } from 'vue'
import { useDataStore, fmtDate, todoHitsDay, isRepeat, repeatDoneOn, repeatHits } from '../stores/data'
import ProgressBar from '../components/ProgressBar.vue'

const store = useDataStore()

/* 最近 N 天序列 */
function lastNDays(n) {
  const days = []
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i)
    const f = fmtDate(d) // 本地时区，与数据写入侧 today() 同口径（toISOString 在凌晨会错位到昨天）
    const wd = '日一二三四五六'[d.getDay()]
    days.push({ f, label: `${d.getMonth() + 1}/${d.getDate()}`, wd })
  }
  return days
}

const days7 = computed(() => lastNDays(7))
const days30 = computed(() => lastNDays(30))

/* 工时多源聚合：pomodoros 表（番茄完成的工时）+ worklogs 表中 source≠pomodoro（手动/其他工时）
   这样无论番茄从哪个入口完成（手动 / autoPomoOnTodo / autoPomoOnAction / 番茄钟到点）都自动计入，
   不需要在每个 create('pomodoros', ...) 旁再写一条 worklog。 */
function sumWorkMinutes(f) {
  const fromPomo = store.pomodoros.filter(p => p.date === f).reduce((s, p) => s + (p.minutes || 25), 0)
  const fromManual = store.worklogs.filter(w => w.date === f && w.source !== 'pomodoro').reduce((s, w) => s + (w.minutes || 0), 0)
  return fromPomo + fromManual
}

/* 番茄趋势（7 日） */
const pomoData = computed(() => days7.value.map(d => ({
  ...d,
  count: store.pomodoros.filter(p => p.date === d.f).length,
  minutes: store.pomodoros.filter(p => p.date === d.f).reduce((s, p) => s + (p.minutes || 25), 0)
})))
const maxPomo = computed(() => Math.max(...pomoData.value.map(d => d.count), 1))

/* 工时（近 7 日） —— 多源聚合（见 sumWorkMinutes） */
const workData = computed(() => days7.value.map(d => ({
  ...d,
  minutes: sumWorkMinutes(d.f)
})))
const maxWork = computed(() => Math.max(...workData.value.map(d => d.minutes), 1))

/* 30 日完成任务趋势 + 每日「需要完成」（用于体现完成率）
   口径说明：
   - 需要(f)：当日列表口径命中的任务数（与「今天」列表同口径 todoHitsDay）：
     普通待办锚定 date；重复行动按规则命中且在生效区间；未投影行动按其锚点日计入。
   - 完成(f)：上述命中任务中已完成的部分（重复行动看 doneDates 是否含当日；
     普通任务看 status==='done'，补做也算完成）。完成 ≤ 需要，完成率 ≤ 100%。
   - 图中"待办/行动"柱 = 完成数按待办 / 行动投影拆分，每个任务只计一次
     （旧版按 doneDate + doneDates 双计行动，已修正口径）。 */
/* 单日「需要/完成」统计 —— 列表、趋势图与指标卡共用同一口径 */
function dayStat(f) {
  const actMap = Object.fromEntries(store.actions.map(a => [a.id, a]))
  const projected = new Set(store.todos.filter(t => t.actionId).map(t => t.actionId))
  let needed = 0, done = 0, todos = 0, actions = 0
  for (const t of store.todos) {
    const a = t.actionId ? actMap[t.actionId] : null
    if (!todoHitsDay(t, a, f)) continue
    needed++
    const ok = isRepeat(a) ? repeatDoneOn(a, f) : t.status === 'done'
    if (ok) { done++; if (a) actions++; else todos++ }
  }
  // 没有待办投影的行动（历史数据/接口直建），按锚点或重复规则单独计入
  for (const a of store.actions) {
    if (projected.has(a.id)) continue
    const hit = isRepeat(a)
      ? repeatHits(a, f) && (!a.startDate || a.startDate <= f) && (!a.endDate || a.endDate >= f)
      : (a.startDate || a.date) === f
    if (!hit) continue
    needed++
    const ok = isRepeat(a) ? repeatDoneOn(a, f) : a.status === 'done'
    if (ok) { done++; actions++ }
  }
  return { todos, actions, needed, done }
}
const doneTrend = computed(() => {
  return days30.value.map(d => {
    const s = dayStat(d.f)
    return { ...d, ...s, rate: s.needed ? Math.round(s.done / s.needed * 100) : null }
  })
})
const maxDone = computed(() => Math.max(...doneTrend.value.map(d => Math.max(d.done, d.needed)), 1))
// 30 日平均完成率（只对有任务安排的日子取均值，空一天不算 0%）
const avgRate = computed(() => {
  const list = doneTrend.value.filter(d => d.needed > 0)
  if (!list.length) return null
  return Math.round(list.reduce((s, d) => s + d.done / d.needed, 0) / list.length * 100)
})
function trendTitle(d) {
  const rate = d.rate == null ? '无任务' : `完成率 ${d.rate}%`
  return `${d.f}: 完成 ${d.done}/${d.needed} · ${rate}（待办 ${d.todos} · 行动 ${d.actions}）`
}

/* 效率指标 */
const metrics = computed(() => {
  const week = days7.value.map(d => d.f)
  const totalTodos = store.todos.filter(t => week.includes(t.doneDate)).length
  const totalActions = store.actions.filter(a => a.doneDates?.some(dd => week.includes(dd))).length
  const totalPomos = store.pomodoros.filter(p => week.includes(p.date)).length
  const totalMinutes = week.reduce((s, d) => s + sumWorkMinutes(d), 0)
  const goal = store.settings.dailyGoalMinutes || 0

  // 计划偏差：本周计划（近 7 日逐日累计，与上方「本周」指标同口径，含行动投影/重复行动） vs 本周实际完成
  let planned = 0, actual = 0
  for (const d of days7.value) { const s = dayStat(d.f); planned += s.needed; actual += s.done }
  const deviation = planned ? Math.round(((actual - planned) / planned) * 100) : 0

  // 连续执行天数（以完成任意任务/番茄为准）
  const activeSet = new Set([
    ...store.pomodoros.map(p => p.date),
    ...store.todos.filter(t => t.doneDate).map(t => t.doneDate),
    ...store.actions.flatMap(a => a.doneDates || [])
  ])
  let streak = 0
  const d = new Date()
  while (activeSet.has(fmtDate(d))) { streak++; d.setDate(d.getDate() - 1) }

  // 总番茄
  const totalPomoAll = store.pomodoros.length
  const totalMinutesAll = store.pomodoros.reduce((s, p) => s + (p.minutes || 25), 0)

  return { totalTodos, totalActions, totalPomos, totalMinutes, goal, planned, actual, deviation, streak, totalPomoAll, totalMinutesAll }
})

/* 近 7 日番茄按任务占比（分组依据：番茄记录绑定的目标 targetId，展示名取当前待办/行动名，
   目标已被删除时回落到记录里的 title 快照；真正没绑定目标的才归为「自由专注」） */
const pomoPalette = ['#9b6dff', '#6cc7a5', '#f5a35c', '#5aa9e6', '#f26d76', '#c9a227', '#8a8fa3']
const pomoTaskDist = computed(() => {
  const week = days7.value.map(d => d.f)
  const list = store.pomodoros.filter(p => week.includes(p.date))
  const nameOf = (p) => {
    if (p.targetId) {
      const hit = store.todos.find(t => t.id === p.targetId) || store.actions.find(a => a.id === p.targetId)
      if (hit) return (hit.text || hit.name || '').trim() || '未命名'
    }
    return (p.title || '').trim() || '自由专注'
  }
  const map = {}
  for (const p of list) {
    const name = nameOf(p)
    map[name] = (map[name] || 0) + 1
  }
  const items = Object.entries(map).map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count)
  // 只画前 6 个任务，其余合并为「其他」，避免扇区过碎
  const top = items.slice(0, 6)
  const rest = items.slice(6).reduce((s, x) => s + x.count, 0)
  if (rest) top.push({ name: '其他', count: rest })
  const total = list.length
  let acc = 0
  const segs = top.map((x, i) => {
    const pct = total ? x.count / total * 100 : 0
    const seg = { ...x, pct, from: acc, color: pomoPalette[i % pomoPalette.length] }
    acc += pct
    return seg
  })
  const gradient = segs.map(s => `${s.color} ${s.from}% ${acc < 100 && s === segs[segs.length - 1] ? 100 : s.from + s.pct}%`).join(', ')
  return { total, segs, gradient }
})

/* 任务完成分布（按项目） */
const projDist = computed(() => {
  const map = {}
  for (const t of store.todos) {
    if (!t.projectId) continue
    const p = store.projectMap[t.projectId]
    const name = p?.name || '未命名'
    map[name] = map[name] || { name, done: 0, total: 0 }
    map[name].total++
    if (t.status === 'done') map[name].done++
  }
  return Object.values(map)
    .sort((a, b) => (a.done / a.total) - (b.done / b.total))
    .slice(0, 8)
})

/* 分布块顶部汇总：全部关联项目待办的已完成/未完成数（与 projDist 同口径，不受 top8 截断影响） */
const projSummary = computed(() => {
  let done = 0, total = 0
  for (const t of store.todos) {
    if (!t.projectId) continue
    total++
    if (t.status === 'done') done++
  }
  return { done, undone: total - done, total }
})

/* ============ 工时热力图（GitHub 风格） ============ */
/* 工时口径与「工时记录」一致：pomodoros + worklogs(source≠pomodoro)，详见 sumWorkMinutes */
const heatMode = ref('365') // '365' = 最近365天 | 'year' = 当年

const heatData = computed(() => {
  const now = new Date()
  const today = fmtDate(now)
  // 「当年」模式画满整年：即使还没到的日子也占位展示，热力图才是完整的 12 个月
  // 「最近 365 天」是滚动区间，右端必然停在今天
  const end = heatMode.value === 'year'
    ? new Date(now.getFullYear(), 11, 31)
    : new Date(now)
  const start = heatMode.value === 'year'
    ? new Date(now.getFullYear(), 0, 1)
    : new Date(now.getFullYear(), now.getMonth(), now.getDate() - 364)
  const rangeStart = fmtDate(start) // 实际数据区间的第一天（本地时区，与工时记录口径一致）
  // 左端对齐到周日，保证每 7 天一列（GitHub 布局）
  start.setDate(start.getDate() - start.getDay())
  // 按日聚合工时（多源）
  const byDay = {}
  for (const p of store.pomodoros) {
    if (!p.date) continue
    byDay[p.date] = (byDay[p.date] || 0) + (p.minutes || 25)
  }
  for (const w of store.worklogs) {
    if (!w.date || w.source === 'pomodoro') continue
    byDay[w.date] = (byDay[w.date] || 0) + (w.minutes || 0)
  }
  const days = []
  let lastMonth = -1
  for (const d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    const f = fmtDate(d)
    const inRange = f >= rangeStart
    const future = f > today // 今天之后：占位格，不算进任何汇总
    const minutes = byDay[f] || 0
    // 每月首个范围内的日子打上月份标签（供列头展示）
    let monthLabel = ''
    if (inRange && d.getMonth() !== lastMonth) {
      lastMonth = d.getMonth()
      monthLabel = `${d.getMonth() + 1}月`
    }
    days.push({
      f, minutes, inRange, future, monthLabel,
      title: !inRange ? '' : future ? `${f} · 未到` : `${f} · ${minutes ? (minutes / 60).toFixed(1) + ' 小时' : '无记录'}`
    })
  }
  return days
})

/* 按周分列，同时提取每列的月份标签（取列内第一个非空标签） */
const heatWeeks = computed(() => {
  const days = heatData.value
  const weeks = []
  for (let i = 0; i < days.length; i += 7) {
    const col = days.slice(i, i + 7)
    weeks.push({ days: col, monthLabel: (col.find(d => d.monthLabel) || {}).monthLabel || '' })
  }
  return weeks
})

const heatMax = computed(() => Math.max(...heatData.value.map(d => d.minutes), 1))

/* 0-4 级色阶：按单日工时占最大值的比例分档 */
function heatLevel(m, max) {
  if (!m) return 0
  const q = max / 4
  return m <= q ? 1 : m <= q * 2 ? 2 : m <= q * 3 ? 3 : 4
}

/* 区间汇总 */
const heatSummary = computed(() => {
  const inRange = heatData.value.filter(d => d.inRange)
  const totalMin = inRange.reduce((s, d) => s + d.minutes, 0)
  const activeDays = inRange.filter(d => d.minutes > 0).length
  const best = inRange.reduce((b, d) => (d.minutes > (b?.minutes || 0) ? d : b), null)
  return {
    hours: (totalMin / 60).toFixed(1),
    activeDays,
    bestHours: best ? (best.minutes / 60).toFixed(1) : '0',
    bestDate: best?.f || '—'
  }
})
const heatWeekLabels = ['', '一', '', '三', '', '五', '']
</script>

<template>
  <div class="main-inner">
    <div class="page-head">
      <div>
        <div class="page-title">数据统计</div>
        <div class="page-sub">所有数字都来自你的真实操作记录——番茄钟、完成待办、工作笔记。</div>
      </div>
    </div>

    <!-- 核心指标 -->
    <div class="grid-4 mb-16">
      <div class="card metric"><div class="m-label">本周完成任务</div><div class="m-num">{{ metrics.totalTodos + metrics.totalActions }}</div><div class="muted">待办 {{ metrics.totalTodos }} + 行动 {{ metrics.totalActions }}</div></div>
      <div class="card metric"><div class="m-label">本周番茄</div><div class="m-num">{{ metrics.totalPomos }}</div><div class="muted">累计 {{ metrics.totalPomoAll }} 个 · {{ Math.round(metrics.totalMinutesAll / 60) }} 小时</div></div>
      <div class="card metric"><div class="m-label">本周计划偏差</div>
        <div class="m-num" :style="{ color: metrics.deviation >= 0 ? 'var(--green)' : 'var(--red)' }">{{ metrics.deviation >= 0 ? '+' : '' }}{{ metrics.deviation }}%</div>
        <div class="muted">计划 {{ metrics.planned }} · 完成 {{ metrics.actual }}</div></div>
      <div class="card metric"><div class="m-label">连续执行</div><div class="m-num">🔥 {{ metrics.streak }}</div><div class="muted">天（完成任意任务/番茄）</div></div>
    </div>

    <!-- 近 7 日三图并列：两张柱状图更宽，窄屏依次降级为两列/一列 -->
    <div class="charts-grid">
      <!-- 番茄 7 日 -->
      <div class="card">
        <h3 style="font-size:15px;margin-bottom:14px">番茄钟 · 近 7 日</h3>
        <div class="bar-chart">
          <div v-for="d in pomoData" :key="d.f" class="bar-col">
            <div class="bar-val">{{ d.count || '' }}</div>
            <div class="bar-track">
              <div class="bar-fill pomo" :style="{ height: (d.count / maxPomo * 100) + '%' }"></div>
            </div>
            <div class="bar-label">{{ d.wd }}</div>
          </div>
        </div>
      </div>

      <!-- 工时 7 日 -->
      <div class="card">
        <h3 style="font-size:15px;margin-bottom:14px">工时记录 · 近 7 日（分钟）</h3>
        <div class="bar-chart">
          <div v-for="d in workData" :key="d.f" class="bar-col">
            <div class="bar-val">{{ d.minutes ? d.minutes + 'm' : '' }}</div>
            <div class="bar-track">
              <div class="bar-fill work" :style="{ height: (d.minutes / maxWork * 100) + '%' }"></div>
            </div>
            <div class="bar-label">{{ d.wd }}</div>
          </div>
        </div>
      </div>

      <!-- 近 7 日番茄任务占比 -->
      <div class="card pie-card">
        <h3 style="font-size:15px;margin-bottom:14px">番茄钟任务占比 · 近 7 日</h3>
        <div v-if="pomoTaskDist.total" class="pie-wrap">
          <div class="pie" :style="{ background: `conic-gradient(${pomoTaskDist.gradient})` }">
            <div class="pie-hole">
              <div class="pie-total">{{ pomoTaskDist.total }}</div>
              <div class="muted" style="font-size:11px">番茄</div>
            </div>
          </div>
          <div class="pie-legend">
            <div v-for="s in pomoTaskDist.segs" :key="s.name" class="legend-row">
              <i :style="{ background: s.color }"></i>
              <span class="truncate lg-name">{{ s.name }}</span>
              <span class="muted mono" style="margin-left:auto;white-space:nowrap">{{ s.count }} 个 · {{ s.pct.toFixed(0) }}%</span>
            </div>
          </div>
        </div>
        <div v-else class="empty" style="padding:24px">近 7 日还没有番茄记录 🍅</div>
      </div>
    </div>

    <!-- 30 日趋势 -->
    <div class="card mt-16">
      <div class="flex-between" style="margin-bottom:14px;flex-wrap:wrap;gap:8px">
        <h3 style="font-size:15px">完成任务 · 近 30 日</h3>
        <span class="muted" style="font-size:12px">平均完成率 <b :style="{ color: 'var(--primary)' }">{{ avgRate == null ? '—' : avgRate + '%' }}</b></span>
      </div>
      <div class="flex gap-12" style="flex-wrap:wrap;margin-bottom:10px">
        <span class="legend"><i style="background:#9b6dff"></i>完成 · 待办</span>
        <span class="legend"><i style="background:#c7b8ff"></i>完成 · 行动</span>
        <span class="legend"><i style="background:#e6dff5;border:1px dashed #c9bce8"></i>需要完成（背景柱，柱内越满完成率越高）</span>
      </div>
      <div class="trend-wrap">
        <div class="trend-bars">
          <div v-for="d in doneTrend" :key="d.f" class="trend-col" :title="trendTitle(d)">
            <i class="trend-need" :style="{ height: (d.needed / maxDone * 100) + '%' }"></i>
            <div class="trend-seg todo" :style="{ height: (d.todos / maxDone * 100) + '%' }"></div>
            <div class="trend-seg act" :style="{ height: (d.actions / maxDone * 100) + '%' }"></div>
          </div>
        </div>
        <div class="flex-between muted mt-8" style="font-size:11px">
          <span>{{ doneTrend[0]?.f }}</span><span>{{ doneTrend[Math.floor(29 / 2)]?.f }}</span><span>{{ doneTrend[29]?.f }}</span>
        </div>
      </div>
    </div>

    <!-- 工时热力图（GitHub 风格） -->
    <div class="card mt-16">
      <div class="flex-between" style="margin-bottom:12px;flex-wrap:wrap;gap:8px">
        <h3 style="font-size:15px">工时热力图</h3>
        <div class="heat-tabs">
          <button :class="{ on: heatMode === '365' }" @click="heatMode = '365'">最近 365 天</button>
          <button :class="{ on: heatMode === 'year' }" @click="heatMode = 'year'">当年 {{ new Date().getFullYear() }}</button>
        </div>
      </div>
      <div class="muted" style="font-size:12px;margin-bottom:12px">
        {{ heatSummary.hours }} 小时 · 有记录 {{ heatSummary.activeDays }} 天 · 单日最高 {{ heatSummary.bestHours }} 小时（{{ heatSummary.bestDate }}）
      </div>
      <div class="heat-wrap">
        <div class="heat-wdcol">
          <i v-for="(w, i) in heatWeekLabels" :key="'wd' + i">{{ w }}</i>
        </div>
        <div class="heat-body">
          <div class="heat-months">
            <span v-for="(wk, wi) in heatWeeks" :key="'m' + wi">{{ wk.monthLabel }}</span>
          </div>
          <div class="heat-cols">
            <div v-for="(wk, wi) in heatWeeks" :key="'c' + wi" class="heat-col">
              <i
                v-for="d in wk.days" :key="d.f"
                :class="['heat-cell', 'h' + heatLevel(d.minutes, heatMax), { out: !d.inRange, future: d.future }]"
                :title="d.title"
              ></i>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 项目分布 -->
    <div class="card mt-16">
      <div class="flex-between" style="margin-bottom:14px;flex-wrap:wrap;gap:8px">
        <h3 style="font-size:15px">待办完成分布（按项目）</h3>
        <div class="dist-summary" v-if="projSummary.total">
          <span class="ds-item done"><i></i>已完成 <b>{{ projSummary.done }}</b></span>
          <span class="ds-item undone"><i></i>未完成 <b>{{ projSummary.undone }}</b></span>
        </div>
      </div>
      <div v-for="p in projDist" :key="p.name" class="dist-row">
        <span class="muted truncate dist-name">{{ p.name }}</span>
        <div class="grow"><ProgressBar :value="p.total ? Math.round(p.done / p.total * 100) : 0" /></div>
        <span class="muted mono" style="width:52px;text-align:right">{{ p.done }}/{{ p.total }}</span>
      </div>
      <div v-if="!projDist.length" class="empty" style="padding:24px">暂无关联项目的待办</div>
    </div>
  </div>
</template>

<style scoped>
.metric { padding: 16px 18px; }
.m-label { font-size: 12px; color: var(--text-2); margin-bottom: 4px; }
.m-num { font-size: 26px; font-weight: 700; letter-spacing: -0.5px; line-height: 1.3; }

.bar-chart { display: flex; gap: 10px; align-items: flex-end; height: 180px; }
.bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; height: 100%; justify-content: flex-end; }
.bar-val { font-size: 11px; color: var(--text-2); height: 16px; }
.bar-track { width: 100%; max-width: 34px; flex: 1; display: flex; align-items: flex-end; background: var(--surface-2); border-radius: 8px; overflow: hidden; }
.bar-fill { width: 100%; border-radius: 8px; transition: height .4s ease; }
.bar-fill.pomo { background: linear-gradient(180deg, #bda6fe, #9b6dff); }
.bar-fill.work { background: linear-gradient(180deg, #6cc7a5, #30a46c); }
.bar-label { font-size: 11px; color: var(--text-3); }

.trend-wrap { height: 200px; }
.trend-bars { display: flex; gap: 2px; align-items: stretch; height: 190px; }
.trend-col { flex: 1; display: flex; flex-direction: column-reverse; gap: 0; height: 100%; min-height: 0; position: relative; }
.trend-seg { width: 100%; border-radius: 1px 1px 0 0; min-height: 0; position: relative; z-index: 1; }
.trend-seg.todo { background: #9b6dff; }
.trend-seg.act { background: #c7b8ff; }
/* 「需要完成」背景柱：垫在完成柱后面，直观对比每天的计划量与实际完成 */
.trend-need { position: absolute; left: 0; right: 0; bottom: 0; z-index: 0; background: #e9e2f7; border-radius: 1px 1px 0 0; }

.legend { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-2); }
.legend i { width: 10px; height: 10px; border-radius: 3px; display: inline-block; }

.dist-row { display: flex; align-items: center; gap: 10px; padding: 7px 0; }
.dist-name { width: 110px; flex-shrink: 0; }

/* 分布块顶部汇总徽标 */
.dist-summary { display: inline-flex; gap: 8px; }
.ds-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-2); padding: 4px 10px; border-radius: 999px; background: var(--surface-2); }
.ds-item i { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.ds-item b { font-weight: 700; color: var(--text); }
.ds-item.done i { background: var(--green, #30a46c); }
.ds-item.undone i { background: var(--primary); }

/* 三图并列：柱状图 2.5:2.5、饼图 3；网格默认 stretch 保证三卡等高。
   用 minmax(0, …) 而非裸 fr：裸 fr 的下限是内容最小宽，含 nowrap 文本时会被撑破容器 */
.charts-grid { display: grid; grid-template-columns: minmax(0, 2.5fr) minmax(0, 2.5fr) minmax(0, 3fr); gap: 14px; }
.pie-card { display: flex; flex-direction: column; }
@media (max-width: 1100px) {
  .charts-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .pie-card { grid-column: 1 / -1; } /* 平板宽度下饼图独占一行，图例稳定在环图右侧 */
}
@media (max-width: 620px) {
  .charts-grid { grid-template-columns: minmax(0, 1fr); } /* 手机每行一图 */
}

.pie-wrap { flex: 1; display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.pie { width: 132px; height: 132px; border-radius: 50%; position: relative; flex: none; }
.pie-hole { position: absolute; inset: 28px; background: var(--surface, #fff); border-radius: 50%; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.pie-total { font-size: 20px; font-weight: 700; line-height: 1.2; }
.pie-legend { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.legend-row { display: flex; align-items: center; gap: 8px; padding: 5px 0; font-size: 12px; }
.legend-row i { width: 10px; height: 10px; border-radius: 3px; flex: none; }
/* 图例名称：桌面限宽避免长项目名挤压右侧数量，窄屏改为自适应占满剩余宽度 */
.lg-name { max-width: 260px; }

/* 工时热力图 */
.heat-tabs { display: flex; gap: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.heat-tabs button { border: none; background: transparent; padding: 5px 12px; font-size: 12px; color: var(--text-2); cursor: pointer; }
.heat-tabs button + button { border-left: 1px solid var(--border); }
.heat-tabs button.on { background: var(--primary); color: #fff; }

.heat-wrap { display: flex; gap: 6px; }
.heat-wdcol { display: flex; flex-direction: column; gap: 3px; padding-top: 16px; flex: none; width: 16px; }
.heat-wdcol i { height: 16px; line-height: 16px; font-size: 10px; font-style: normal; color: var(--text-3); text-align: right; }
.heat-body { flex: 1; min-width: 0; }
.heat-months { display: flex; gap: 3px; margin-bottom: 3px; height: 13px; }
.heat-months span { flex: 1; min-width: 0; font-size: 10px; color: var(--text-3); white-space: nowrap; overflow: visible; }
.heat-cols { display: flex; gap: 3px; }
.heat-col { display: flex; flex-direction: column; gap: 3px; flex: 1; min-width: 0; }
.heat-cell { width: 100%; height: 16px; border-radius: 2px; display: inline-block; }
.heat-cell.h0 { background: var(--surface-2); }
.heat-cell.h1 { background: #d5f0e3; }
.heat-cell.h2 { background: #96dcbd; }
.heat-cell.h3 { background: #55c39b; }
.heat-cell.h4 { background: #2f9f6d; }
/* 还没到的日子：占位展示，用虚线描边与「过去但当天没记录」的实心格子区分开 */
.heat-cell.future { background: transparent; border: 1px dashed var(--border); }
.heat-cell.out { background: transparent; cursor: default; }
.heat-cell:not(.out) { cursor: default; }

/* =====================================================================
   移动端（≤ 820px / 手机横屏）
   ---------------------------------------------------------------------
   注意：这一段必须放在文件末尾。此前的规则写在 .heat-wrap / .heat-body /
   .heat-tabs button 等基础样式之前，同为「类 + 元素」选择器、权重相同，
   后者按源顺序反超，导致 .heat-body 的 min-width:560px 被覆盖成 0——
   365 天热力图的横向滚动修复其实从未生效（格子被压到 5px 宽），
   热力图区间按钮的加高也被吃掉。放在末尾才能稳定生效。
   ===================================================================== */
@media (max-width: 820px), (pointer: coarse) and (max-height: 480px) {
  /* 工时热力图：365 天 = 53 列，手机上等比压缩后每格只剩 5px 左右，看不清也点不准。
     改为固定格子宽度 + 容器横向滚动（与 GitHub 移动端一致），纵向刻度保持不变避免错位。 */
  .heat-wrap {
    overflow-x: auto;
    overflow-y: hidden;
    padding-bottom: 8px;
    -webkit-overflow-scrolling: touch;
  }
  .heat-body { min-width: 560px; }

  /* 图例名称改为占满剩余宽度（原先固定 max-width:260px 会与右侧数量一起把行挤爆） */
  .lg-name { flex: 1; min-width: 0; max-width: none; }

  .dist-name { width: 82px; }
  .m-num { font-size: 23px; }
  .metric { padding: 13px 14px; }
  .bar-chart { height: 150px; }
  .trend-wrap, .trend-bars { height: 158px; }
  .pie { width: 112px; height: 112px; }
  .pie-hole { inset: 24px; }
  /* 热力图区间切换按钮加高到 36px 以上，方便触摸 */
  .heat-tabs button { padding: 10px 14px; font-size: 12.5px; }
  .ds-item { padding: 3px 9px; }
}
</style>
