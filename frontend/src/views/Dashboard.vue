<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore, nextDueDate, isRepeat, today, sortProjectCards } from '../stores/data'
import { useAuthStore } from '../stores/auth'
import { useInlineRename } from '../composables/useInlineRename'
import StatusBadge from '../components/StatusBadge.vue'
import ProgressBar from '../components/ProgressBar.vue'

const store = useDataStore()
const auth = useAuthStore()
const quickText = ref('')
const quickPri = ref('P3')

/* 今日到期行动：双击名字就地改名（name 会同步到待办侧投影条目） */
const { renamingId, renameText, startRename, commitRename, cancelRename, renameRef } =
  useInlineRename(async (id, text) => {
    const a = store.actions.find(x => x.id === id)
    if (!a) return
    if (!text) { store.toast('行动名称不能为空', 'err'); return }
    if (text === a.name) return
    await store.updateAction(id, { name: text }, { msg: '已重命名' })
  })

/* ===== 问候语 =====
   按当前时段选择问候词（打开首页时取值即可，不必分钟级刷新）；
   昵称用 auth.displayName（nickname 优先、username 兜底），两者都空则只显示问候语。 */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h >= 6 && h < 12) return '早上好'
  if (h >= 12 && h < 18) return '下午好'
  return '晚上好'
})
const greetingText = computed(() =>
  auth.displayName ? `${greeting.value}，${auth.displayName}` : greeting.value
)

/* ===== 番茄钟 =====
   会话状态存在 store（endAt 结束时间戳），倒计时由 endAt - now 推导而非累加计数，
   所以切换到别的视图再回来计时不会丢。单位统一为「秒」（fmt 按秒格式化）。 */
const now = ref(Date.now())
const bindTodo = ref('') // 番茄钟绑定的待办（非必选）
let tickHandle = null

const timerOn = computed(() => !!store.pomoSession.endAt)
// 运行中取会话启动时锁定的时长，避免中途改设置导致进度环跳动
const timerTotal = computed(() => (timerOn.value ? store.pomoSession.minutes : store.pomoMinutes) * 60)
const timerLeft = computed(() =>
  timerOn.value ? Math.max(0, Math.ceil((store.pomoSession.endAt - now.value) / 1000)) : timerTotal.value
)

const todayTodoRows = computed(() => store.todayTodoRows)
const todayTodos = computed(() => todayTodoRows.value.slice(0, 8))
/* 项目进度：取前 6 个进行中的项目。
   排序统一走 sortProjectCards：先按优先级 P1>P2>P3，再按起止日期倒序。
   已完成项目不进首页摘要（filter 在前）；sort 之后 slice 保证拿到的就是优先级最高、日期最近的进行中项目 */
const activeProjects = computed(() => sortProjectCards(store.projectCards.filter(p => p.status !== 'done')).slice(0, 6))
const dueActions = computed(() => {
  const td = today()
  return store.actions
    .filter(a => {
      if (isRepeat(a)) {
        // 重复行动：今天已完成(记于 doneDates)则不再提示；否则即使昨日做过今天也照常出现
        if ((a.doneDates || []).includes(td)) return false
        return true
      }
      return a.status !== 'done'
    })
    .map(a => ({ ...a, due: nextDueDate(a.repeat, a.doneDates?.length ? a.doneDates[a.doneDates.length - 1] : a.startDate || undefined) }))
    .filter(a => a.due && a.due <= td)
    .sort((a, b) => a.due.localeCompare(b.due))
    .slice(0, 5)
})
const bindableTodos = computed(() => {
  const list = todayTodoRows.value.filter(r => !r.done).map(r => r.todo)
  // 会话可能在待办页发起（专注的待办未必属于今日列表），缺失会让下拉显示空白
  const sid = store.pomoSession.todoId
  if (sid && !list.some(t => t.id === sid)) {
    const t = store.todos.find(x => x.id === sid)
    if (t) return [t, ...list]
  }
  return list
})

async function quickAdd() {
  const text = quickText.value.trim()
  if (!text) return
  await store.addTodo({ text, date: today(), status: 'todo', priority: quickPri.value })
  quickText.value = ''
}

function startTick() {
  stopTick()
  tickHandle = setInterval(() => {
    now.value = Date.now()
    if (store.pomoSession.endAt && now.value >= store.pomoSession.endAt) {
      stopTick()
      finish()
    }
  }, 250)
}
function stopTick() {
  if (tickHandle) clearInterval(tickHandle)
  tickHandle = null
}
function startTimer() {
  if (timerOn.value) return
  store.startPomoSession({ minutes: store.pomoMinutes, todoId: bindTodo.value })
  now.value = Date.now()
  startTick()
}
function stopTimer() {
  store.stopPomoSession()
  stopTick()
}
async function finish() {
  stopTick()
  now.value = Date.now()
  await store.finishPomoSession()
}
const fmt = (s) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`

// 会话变化时同步本地计时。用 watch 而非只在 onMounted 查一次：
// 应用启动后 store 会从 localStorage 恢复刷新前的会话，那发生在本组件挂载之后。
watch(() => store.pomoSession.endAt, (endAt) => {
  if (!endAt) return
  bindTodo.value = store.pomoSession.todoId || '' // 回填绑定，否则下拉会跳回「不绑定」
  if (Date.now() >= endAt) finish() // 已过期 → 补记这一颗番茄
  else startTick()
}, { immediate: true })
// 只停本地 tick，不动 store 会话 —— 这样切页面不会把专注进度弄丢
onBeforeUnmount(stopTick)
</script>

<template>
  <div class="main-inner">
    <div class="page-head">
      <div>
        <div class="page-title">{{ greetingText }} 👋</div>
        <div class="page-sub">专注把重要的事做扎实。今天已完成 {{ store.doneTodos.filter(t => t.doneDate === today()).length }} 件待办，{{ store.todayPomos }} 个番茄钟。</div>
      </div>
      <div class="flex gap-8">
        <button class="btn" @click="$router.push('/stats')">◔ 数据统计</button>
        <button class="btn primary" @click="$router.push('/todo')">＋ 新建待办</button>
      </div>
    </div>

    <!-- 统计卡 -->
    <div class="grid-4 mb-16">
      <div class="card stat-card">
        <div class="stat-ico" style="background:#f1ebff;color:#8a6add">☑</div>
        <div><div class="stat-num">{{ todayTodoRows.length }}</div><div class="muted">今日待办</div></div>
      </div>
      <div class="card stat-card">
        <div class="stat-ico" style="background:#e7f6ee;color:#2a8f5e">✓</div>
        <div><div class="stat-num">{{ todayTodoRows.filter(r => r.done).length }}</div><div class="muted">完成待办</div></div>
      </div>
      <div class="card stat-card">
        <div class="stat-ico" style="background:#f1ebff;color:#9b6dff">🍅</div>
        <div><div class="stat-num">{{ store.todayPomos }}</div><div class="muted">今日番茄</div></div>
      </div>
      <div class="card stat-card">
        <div class="stat-ico" style="background:#e7f6ee;color:#2a8f5e">◷</div>
        <div><div class="stat-num">{{ store.todayMinutes }}</div><div class="muted">今日工时（分钟）</div></div>
      </div>
      <div class="card stat-card">
        <div class="stat-ico" style="background:#fef0e4;color:#dd5f0c">🔥</div>
        <div><div class="stat-num">{{ store.streak }}</div><div class="muted">连续专注天数</div></div>
      </div>
    </div>

    <div class="grid-2 dash-cols">
      <!-- 左列 -->
      <div class="flex-col gap-16" style="display:flex;flex-direction:column;gap:16px">
        <!-- 快速创建 -->
        <div class="card">
          <div class="quick-add">
            <span class="qa-ico">＋</span>
            <input v-model="quickText" class="qa-input" placeholder="快速添加待办，回车创建…" @keydown.enter="quickAdd" />
            <select v-model="quickPri" class="select qa-pri">
              <option value="P1">P1</option>
              <option value="P2">P2</option>
              <option value="P3">P3</option>
            </select>
            <button class="btn primary" @click="quickAdd">添加</button>
          </div>
        </div>

        <!-- 项目进度 -->
        <div class="card">
          <div class="flex-between mb-12">
            <h3 style="font-size:15px">项目进度</h3>
            <button class="btn ghost sm" @click="$router.push('/ongoing')">查看全部 →</button>
          </div>
          <div v-if="!activeProjects.length" class="empty" style="padding:24px">暂无进行中的项目</div>
          <div v-for="p in activeProjects" :key="p.id" class="proj-row">
            <div class="flex gap-8 grow">
              <span class="proj-dot" :style="{ background: p.color || '#9b6dff' }"></span>
              <span class="grow truncate" style="font-weight:500">{{ p.name }}</span>
              <span class="muted">{{ p.doneCount }}/{{ p.totalCount }}</span>
            </div>
            <div class="proj-bar"><ProgressBar :value="p.progress" /></div>
            <span class="muted mono" style="width:38px;text-align:right">{{ p.progress }}%</span>
          </div>
        </div>

        <!-- 今日行动 -->
        <div class="card">
          <h3 style="font-size:15px;margin-bottom:12px">今日到期行动</h3>
          <div v-if="!dueActions.length" class="empty" style="padding:20px">今天没有到期行动，享受从容 🍵</div>
          <div v-for="a in dueActions" :key="a.id" class="act-row">
            <div class="checkbox" :class="{ on: false }" @click="store.completeAction(a)">✓</div>
            <div class="grow">
              <input v-if="renamingId === a.id" :ref="renameRef" v-model="renameText" class="rename-input"
                     @keydown.enter.prevent="commitRename(a.id)" @keydown.esc.prevent="cancelRename"
                     @blur="commitRename(a.id)" />
              <div v-else class="truncate" title="双击可改名" @dblclick.stop="startRename(a.id, a.name)">{{ a.name }}</div>
              <div class="muted">{{ store.taskMap[a.taskId]?.name || store.projectMap[a.projectId]?.name || '' }}</div>
            </div>
            <span class="tag red">今天</span>
          </div>
        </div>
      </div>

      <!-- 右列 -->
      <div class="flex-col gap-16" style="display:flex;flex-direction:column;gap:16px">
        <!-- 番茄钟 -->
        <div class="card pomo-card">
          <h3 style="font-size:15px;text-align:center;margin-bottom:8px">番茄钟</h3>
          <select v-model="bindTodo" :disabled="timerOn" class="select" style="width:100%;margin-bottom:10px;font-size:12px" :title="timerOn ? '专注中，绑定已锁定' : '绑定待办（非必选），完成后自动累计该待办的番茄数'">
            <option value="">不绑定待办（自由专注）</option>
            <option v-for="t in bindableTodos" :key="t.id" :value="t.id">{{ t.text.slice(0, 24) }}</option>
          </select>
          <div class="pomo-ring" :style="{ '--p': timerOn ? ((timerTotal - timerLeft) / timerTotal * 100) + '%' : '0%' }">
            <div class="pomo-time">{{ timerOn ? fmt(timerLeft) : fmt(timerTotal) }}</div>
            <div class="muted">{{ timerOn ? '专注中…' : '点击开始' }}</div>
          </div>
          <button v-if="!timerOn" class="btn primary" style="width:100%;justify-content:center" @click="startTimer">▶ 开始专注</button>
          <button v-else class="btn danger" style="width:100%;justify-content:center" @click="stopTimer">■ 停止</button>
        </div>

        <!-- 今日待办 -->
        <div class="card">
          <div class="flex-between mb-12">
            <h3 style="font-size:15px">今日待办</h3>
            <span class="tag primary">{{ todayTodoRows.length }} 项 · 已完成 {{ todayTodoRows.filter(r => r.done).length }}</span>
          </div>
          <div v-if="!todayTodos.length" class="empty" style="padding:20px">今天没有待办 ✨</div>
          <div v-for="r in todayTodos" :key="r.todo.id" class="act-row" @click="store.toggleTodo(r.todo)">
            <div class="checkbox" :class="{ on: r.done }">✓</div>
            <div class="grow truncate" :style="{ textDecoration: r.done ? 'line-through' : 'none', color: r.done ? 'var(--text-3)' : 'inherit' }">{{ r.todo.text }}</div>
            <span v-if="r.repeat" class="tag cyan xs">↻</span>
            <span class="tag" :class="r.todo.priority === 'P1' ? 'red' : r.todo.priority === 'P2' ? 'orange' : 'gray'">{{ r.todo.priority }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stat-card { display: flex; align-items: center; gap: 14px; padding: 16px 18px; }
.stat-ico { width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 19px; flex-shrink: 0; }
.stat-num { font-size: 22px; font-weight: 700; line-height: 1.2; }

.quick-add { display: flex; align-items: center; gap: 10px; }
.qa-ico { color: var(--primary); font-size: 18px; font-weight: 600; }
.qa-input { flex: 1; border: none; outline: none; font-size: 14.5px; font-family: inherit; color: var(--text); background: transparent; padding: 6px 0; }
.qa-pri { width: 86px; flex-shrink: 0; }

.proj-row { display: grid; grid-template-columns: minmax(0, 1fr) 120px 44px; align-items: center; gap: 10px; padding: 8px 0; }
.proj-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }
.proj-bar { width: 120px; }

.act-row { display: flex; align-items: center; gap: 10px; padding: 9px 6px; border-radius: 9px; cursor: pointer; }
.act-row:hover { background: var(--surface-2); }

.pomo-card { text-align: center; }
.pomo-ring {
  width: 150px; height: 150px; margin: 14px auto 18px;
  border-radius: 50%;
  background: conic-gradient(var(--primary) var(--p), var(--surface-2) 0);
  display: flex; align-items: center; justify-content: center;
  position: relative;
}
.pomo-ring::before {
  content: ''; position: absolute; inset: 9px;
  background: var(--surface); border-radius: 50%;
}
.pomo-time { position: relative; font-size: 32px; font-weight: 700; font-variant-numeric: tabular-nums; letter-spacing: -1px; }
.pomo-ring .muted { position: absolute; bottom: 38px; }

/* 左右两栏在桌面按 1.2 : 0.8 分栏（原先写在行内样式里，媒体查询无法覆盖，
   改为类名以便窄屏切换为单列） */
.dash-cols { grid-template-columns: minmax(0, 1.2fr) minmax(0, .8fr); align-items: start; }

/* ===================== 移动端（≤ 820px） ===================== */
@media (max-width: 820px) {
  /* 单列纵向排布：统计卡 → 快速创建 → 项目进度 → 今日到期 → 番茄钟 → 今日待办 */
  .dash-cols { grid-template-columns: 1fr; }

  /* 快速创建：输入框独占一行，优先级与「添加」按钮落到下一行 */
  .quick-add { flex-wrap: wrap; row-gap: 10px; }
  .qa-input { flex: 1 1 calc(100% - 30px); }
  .quick-add .btn { flex: 1; justify-content: center; }
  .qa-pri { width: 92px; }

  /* 项目进度行：名称一行，进度条 + 百分比一行 */
  .proj-row { display: flex; flex-wrap: wrap; gap: 6px 10px; }
  .proj-row > .flex { flex: 1 1 100%; }
  .proj-bar { flex: 1 1 auto; width: auto; }

  /* 统计卡：两列铺满，图标略收 */
  .stat-card { gap: 10px; padding: 13px 12px; }
  .stat-ico { width: 34px; height: 34px; border-radius: 10px; font-size: 16px; }
  .stat-num { font-size: 19px; }

  /* 番茄钟：进度环按屏宽缩放，避免在 320px 机型上顶到卡片边缘 */
  .pomo-ring { width: 132px; height: 132px; }
  .pomo-time { font-size: 28px; }
  .pomo-ring .muted { bottom: 32px; }

  .act-row { padding: 10px 4px; }
}
</style>
