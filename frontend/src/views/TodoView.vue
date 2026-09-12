<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useDataStore, today, fmtDate, todoHitsDay, repeatHits, repeatDoneOn, isRepeat, checklistItemsOf } from '../stores/data'
import { useInlineRename } from '../composables/useInlineRename'
import Modal from '../components/Modal.vue'
import Checklist from '../components/Checklist.vue'

const store = useDataStore()
const view = ref('list') // list | calendar
const calMonth = ref(new Date())
const showConvert = ref(false)
const convertTarget = ref(null)
const showDetail = ref(false)
const detailTodo = ref(null)
const showAdd = ref(false)
const quickAdd = ref(false)
const newText = ref('')

/* ---- 统一创建表单（单日 / 跨日 / 重复 同一入口） ---- */
const addForm = ref({ text: '', date: today(), endDate: '', priority: 'P3', projectId: '', taskId: '', repeatType: '', weekdays: [1, 3, 5], daysText: '1,15' })
const tasksOf = (projectId) => projectId ? store.tasks.filter(t => t.projectId === projectId) : []
const repeatForm = computed(() => {
  const f = addForm.value
  if (f.repeatType === 'weekly') return { type: 'weekly', weekdays: [...f.weekdays].sort((a, b) => a - b) }
  if (f.repeatType === 'monthly') return { type: 'monthly', days: f.daysText.split(/[,，\s]+/).map(Number).filter(n => n >= 1 && n <= 31) }
  if (f.repeatType) return { type: f.repeatType }
  return null
})
// 预览：重复规则自开始日起命中的日期（到结束日或 +90 天，最多 8 个）
const addPreview = computed(() => {
  const rp = repeatForm.value
  if (!rp) return []
  const f = addForm.value
  const probe = { repeat: rp }
  const start = new Date((f.date || today()) + 'T00:00:00')
  const hardEnd = new Date(start); hardEnd.setDate(hardEnd.getDate() + 90)
  const stop = f.endDate ? new Date(Math.min(new Date(f.endDate + 'T00:00:00'), hardEnd)) : hardEnd
  const out = []
  for (let d = new Date(start); d <= stop && out.length < 8; d.setDate(d.getDate() + 1)) {
    const fs = fmtDate(d)
    if (repeatHits(probe, fs)) out.push(fs)
  }
  return out
})
async function submitAdd() {
  const f = addForm.value
  if (!f.text.trim()) return
  const rp = repeatForm.value
  if (rp) {
    if (rp.type === 'weekly' && !rp.weekdays.length) { store.toast('请至少选择一个星期', 'err'); return }
    if (rp.type === 'monthly' && !rp.days.length) { store.toast('请填写每月日期（如 1,15）', 'err'); return }
    if (f.endDate && f.endDate < (f.date || today())) { store.toast('结束日期不能早于开始日期', 'err'); return }
    await store.addRepeatTodo({
      text: f.text.trim(), startDate: f.date || today(), endDate: f.endDate || null,
      priority: f.priority, projectId: f.projectId || null, taskId: f.taskId || null, repeat: rp
    })
  } else {
    if (f.endDate && f.endDate < (f.date || today())) { store.toast('结束日期不能早于日期', 'err'); return }
    await store.addTodo({
      text: f.text.trim(), date: f.date || today(), startDate: f.date || today(), endDate: f.endDate || null,
      priority: f.priority, projectId: f.projectId || null, taskId: f.taskId || null
    })
  }
  addForm.value = { text: '', date: today(), endDate: '', priority: 'P3', projectId: '', taskId: '', repeatType: '', weekdays: [1, 3, 5], daysText: '1,15' }
  showAdd.value = false
  quickAdd.value = false
  newText.value = ''
}

const actMap = computed(() => Object.fromEntries(store.actions.map(a => [a.id, a])))

/* ---- 行/格状态：重复 todo 的"当日实例"完成态以 action.doneDates 为准（按天） ---- */
const actOf = (todo) => (todo.actionId ? actMap.value[todo.actionId] : null)
const rowRepeatLabel = (todo) => {
  const a = actOf(todo)
  return isRepeat(a) ? repeatLabel(a.repeat) : ''
}

/* ---- 列表分组（历史全部 + 今天至未来三天，按日期倒序） ----
   普通 todo 按 date 落组：历史全保留，未来只到三天内（更远的去日历看）；
   跨日 todo（有 startDate+endDate 的有效区间）除锚点组外，还展开到窗口内的命中日（与日历同口径）；
   重复 todo 是"实例态"，只展开到今天~未来三天的命中日（历史不重放，完成记录在日历/统计里）；
   date 为空（行动创建时未填开始时间）的 todo → 「未分配」组，固定置顶提醒排期。 */
const listWindow = computed(() => {
  const base = new Date(today() + 'T00:00:00')
  const names = ['今天', '明天', '后天']
  const out = []
  for (let i = 0; i < 3; i++) {
    const d = new Date(base); d.setDate(d.getDate() + i)
    out.push({ f: fmtDate(d), label: names[i], isToday: i === 0 })
  }
  return out
})
const UNASSIGNED_KEY = 'unassigned'
const groups = computed(() => {
  const t = today()
  const horizon = listWindow.value[listWindow.value.length - 1].f
  const winMap = Object.fromEntries(listWindow.value.map(d => [d.f, d]))
  const g = []
  const gmap = {}
  const unassigned = [] // date 为空的 todo（行动未填开始日期时投影），与日期分组互斥
  const sortInner = (list) => [...list].sort((x, y) =>
    x.done - y.done || (x.todo.priority || 'P3').localeCompare(y.todo.priority || 'P3'))
  const push = (f, todo, done, isRep) => {
    const day = winMap[f] || { f, label: f, isToday: f === t }
    if (!gmap[f]) { gmap[f] = { label: day.label, date: f, isToday: day.isToday, list: [] }; g.push(gmap[f]) }
    gmap[f].list.push({ todo, done, repeat: isRep, day: f })
  }
  for (const todo of store.todos) {
    const a = actOf(todo)
    if (isRepeat(a)) continue // 重复待办下面按窗口逐日展开
    if (!todo.date) {
      // 未分配：行动创建时未填开始时间 → 待办没有 date 锚点 → 单独成组 + 置顶
      unassigned.push({ todo, done: todo.status === 'done', repeat: false, day: UNASSIGNED_KEY })
      continue
    }
    if (todo.date > horizon) continue
    push(todo.date, todo, todo.status === 'done', false)
  }
  for (const day of listWindow.value) {
    for (const todo of store.todos) {
      const a = actOf(todo)
      if (isRepeat(a)) { if (todoHitsDay(todo, a, day.f)) push(day.f, todo, repeatDoneOn(a, day.f), true); continue }
      // 跨日 todo：区间命中窗口日时逐日展开（锚点日已由上面落组，跳过避免重复）
      if (!todo.date) continue // 未分配 todo 没有 date，也不会形成有效跨日区间
      const span = todo.startDate && todo.endDate && todo.endDate >= todo.startDate
      if (span && day.f !== todo.date && todo.startDate <= day.f && todo.endDate >= day.f) {
        push(day.f, todo, todo.status === 'done', false)
      }
    }
  }
  // 组内：未完成在前（按优先级），已完成沉底
  g.forEach(gr => gr.list.sort((x, y) =>
    x.done - y.done || (x.todo.priority || 'P3').localeCompare(y.todo.priority || 'P3')))
  // 未分配组固定置顶（用户决策：打开就看见「有什么待办还没排期」）
  if (unassigned.length) {
    unassigned.sort((x, y) => x.done - y.done || (x.todo.priority || 'P3').localeCompare(y.todo.priority || 'P3'))
    g.unshift({ label: '未分配', date: UNASSIGNED_KEY, isToday: false, isUnassigned: true, list: unassigned })
  }
  return g.sort((a, b) => {
    if (a.isUnassigned !== b.isUnassigned) return a.isUnassigned ? -1 : 1
    return b.date.localeCompare(a.date)
  })
})
// 列表勾选：普通 todo 翻转状态；重复实例按天切换（未来日期禁止提前完成）
async function toggleRow(r) {
  if (!r.repeat) return store.toggleTodo(r.todo)
  if (r.day > today()) { store.toast('未来的日期先不用标记完成', 'err'); return }
  await store.toggleRepeat(actOf(r.todo), r.day)
}

/* ---- 子项（把一条待办拆成若干小步骤）：行内只需一个开关 + 完成计数 ----
   子项本身只有名字/完成状态/排序，交互交给 Checklist 组件；
   这里只负责"该行是否展开子列表"，默认有条目就展开（加完立刻看得见），手动收起后记住选择。
   绑定行动的待办与行动共用同一份子项，所以 parent 同时带上 actionId 与 todoId。 */
const ckOpen = ref({})
const ckItemsOf = (todo) => checklistItemsOf(store.checklist, { actionId: todo.actionId, todoId: todo.id })
const ckTotal = (todo) => ckItemsOf(todo).length
const ckDone = (todo) => ckItemsOf(todo).filter(i => i.done).length
const ckShown = (todo) => ckOpen.value[todo.id] ?? ckTotal(todo) > 0
const toggleChecklist = (todo) => { ckOpen.value = { ...ckOpen.value, [todo.id]: !ckShown(todo) } }

/* ---- 列表内联改名：双击名字就地编辑（不弹详情窗）；重复实例改的是同一条源待办 ---- */
const { renamingId, renameText, startRename, commitRename, cancelRename, renameRef } =
  useInlineRename(async (id, text) => {
    const t = store.todos.find(x => x.id === id)
    if (!t) return
    if (!text) { store.toast('待办内容不能为空', 'err'); return }
    if (text === t.text) return
    await store.renameTodo(t, text, { msg: '已重命名' })
  })

/* ---- 历史分批加载（瀑布流：滚动到底自动追加一批分组） ---- */
const PAGE = 5
const visibleCount = ref(PAGE)
const shownGroups = computed(() => groups.value.slice(0, visibleCount.value))
const scrollEl = ref(null)
const sentinel = ref(null)
let io = null
function loadMore() {
  if (visibleCount.value >= groups.value.length) return
  visibleCount.value += PAGE
  // 重新探测：若哨兵仍在可视区内则继续追加
  nextTick(() => { if (sentinel.value && io) { io.unobserve(sentinel.value); io.observe(sentinel.value) } })
}
// 哨兵节点随视图切换/分批渲染而重建，watch 到变化后重新挂观察器
watch([sentinel, view], () => {
  if (io) io.disconnect()
  if (sentinel.value && view.value === 'list') {
    if (!io) io = new IntersectionObserver(es => { if (es.some(e => e.isIntersecting)) loadMore() },
      { root: scrollEl.value, rootMargin: '240px' })
    io.observe(sentinel.value)
  }
}, { immediate: true })

/* ---- 日历（每格一个"实例"；重复行动按规则展开到命中日，互不干扰） ---- */
const calDays = computed(() => {
  const y = calMonth.value.getFullYear(), m = calMonth.value.getMonth()
  const first = new Date(y, m, 1)
  const start = new Date(first); start.setDate(1 - first.getDay())
  const days = []
  for (let i = 0; i < 42; i++) {
    const d = new Date(start); d.setDate(start.getDate() + i)
    const f = fmtDate(d)
    const cells = []
    for (const todo of store.todos) {
      const a = actOf(todo)
      if (isRepeat(a)) {
        // 重复行动：规则命中且处于生效区间才显示当天实例；完成态 = doneDates 含该日
        if (!repeatHits(a, f)) continue
        if (todo.startDate && f < todo.startDate) continue
        if (todo.endDate && f > todo.endDate) continue
        cells.push({ todo, act: a, f, repeat: true, done: repeatDoneOn(a, f) })
        continue
      }
      // 非重复：跨日必须同时有 startDate + endDate（缺一边都按单日锚定 date，避免"有开始无结束"被当成无限区间满月漂浮）
      const span = todo.startDate && todo.endDate && todo.endDate >= todo.startDate
      const inDay = span
        ? (todo.startDate <= f && todo.endDate >= f)
        : (todo.date === f)
      if (inDay) cells.push({ todo, act: null, f, repeat: false, done: todo.status === 'done' })
    }
    cells.sort((x, y) => x.done - y.done || (x.todo.priority || 'P3').localeCompare(y.todo.priority || 'P3'))
    days.push({ f, day: d.getDate(), inMonth: d.getMonth() === m, todos: cells, date: f })
  }
  return days
})
const calTitle = computed(() => `${calMonth.value.getFullYear()}年${calMonth.value.getMonth() + 1}月`)
// 日历格展开：默认每格只显示前 3 条，点「+N 更多」展开全部，再点收起
const expandedDays = ref([])
const isDayExpanded = (f) => expandedDays.value.includes(f)
const toggleDayExpand = (f) => {
  expandedDays.value = isDayExpanded(f) ? expandedDays.value.filter(x => x !== f) : [...expandedDays.value, f]
}
const calShown = (d) => isDayExpanded(d.f) ? d.todos : d.todos.slice(0, 3)

// 点击日历格：普通 todo 翻转状态；重复实例只切换当天（未来日期禁止提前完成）
function clickCalCell(c) {
  if (c.repeat) {
    if (c.f > today()) { store.toast('未来的日期先不用标记完成', 'err'); return }
    store.toggleRepeat(c.act, c.f)
  } else {
    store.toggleTodo(c.todo)
  }
}

/* ---- 拖拽改期（列表分组间 / 日历格间 / 拖到底部选任意日期） ---- */
const drag = ref(null) // 拖拽中的 todo
const hoverGroup = ref('')
const hoverDay = ref('')
const hoverNew = ref(false)
const canDrag = (todo) => !isRepeat(actOf(todo))
function dragStart(todo, e) {
  drag.value = todo
  e.dataTransfer.effectAllowed = 'move'
  e.dataTransfer.setData('text/plain', todo.id)
}
function dragEnd() { drag.value = null; hoverGroup.value = ''; hoverDay.value = ''; hoverNew.value = false }
function byId(e) {
  const id = e.dataTransfer.getData('text/plain') || (drag.value && drag.value.id)
  return store.todos.find(t => t.id === id)
}
async function dropOnGroup(g, e) {
  const todo = byId(e); dragEnd()
  if (!todo) return
  if (g.isUnassigned) return // 未分配组不接拖拽：避免误把已分配 todo 拖进来破坏数据；想要回未分配请在详情清空日期
  if (g.date === (todo.date || today())) return
  await store.moveTodoDate(todo, g.date)
}
async function dropOnDay(d, e) {
  const todo = byId(e); dragEnd()
  if (!todo) return
  await store.moveTodoDate(todo, d.f)
}
/* 拖到底部"其他日期"区域 → 弹日期选择（目标日还没有分组时用） */
const showMove = ref(false)
const moveTarget = ref(null)
const moveDate = ref('')
function dropToNew(e) {
  const todo = byId(e)
  dragEnd()
  if (!todo) return
  moveTarget.value = todo
  const tmr = new Date(); tmr.setDate(tmr.getDate() + 1)
  moveDate.value = fmtDate(tmr)
  showMove.value = true
}
async function confirmMove() {
  const t = moveTarget.value
  showMove.value = false
  if (t && moveDate.value) await store.moveTodoDate(t, moveDate.value)
}

/* ---- 行内番茄钟（同一时间只专注一条） ----
   与首页番茄钟共用 store.pomoSession，因此：全局同时只会有一个番茄钟（符合注释里的原始设计意图，
   此前只检查了组件内的 timer 变量，导致首页与待办页能各跑一个），且切换视图不会丢进度。 */
const pomoMin = computed(() => store.pomoMinutes)
const now = ref(Date.now())
let timerHandle = null
const fmt = (s) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`

// 正在专注的待办 id。首页发起的「自由专注」没有 todoId，这里不会在任何一行显示倒计时。
const runningId = computed(() => (store.pomoSession.endAt ? store.pomoSession.todoId : null))
const timerLeft = computed(() =>
  store.pomoSession.endAt ? Math.max(0, Math.ceil((store.pomoSession.endAt - now.value) / 1000)) : 0
)
function startTick() {
  stopTick()
  timerHandle = setInterval(() => {
    now.value = Date.now()
    if (store.pomoSession.endAt && now.value >= store.pomoSession.endAt) {
      stopTick()
      finishTodoTimer()
    }
  }, 250)
}
function stopTick() {
  if (timerHandle) clearInterval(timerHandle)
  timerHandle = null
}
function startTodoTimer(t) {
  if (store.pomoSession.endAt) { store.toast('已有番茄钟进行中，请先结束', 'err'); return }
  store.startPomoSession({ minutes: pomoMin.value, todoId: t.id })
  now.value = Date.now()
  startTick()
}
async function finishTodoTimer() {
  stopTick()
  now.value = Date.now()
  await store.finishPomoSession()
}
function stopTodoTimer() {
  store.stopPomoSession()
  stopTick()
}
// 会话变化时同步本地计时。用 watch 而非只在 onMounted 查一次：
// 应用启动后 store 会从 localStorage 恢复刷新前的会话，那发生在本组件挂载之后。
watch(() => store.pomoSession.endAt, (endAt) => {
  if (!endAt) return
  if (Date.now() >= endAt) finishTodoTimer() // 已过期 → 补记这一颗番茄
  else startTick()
}, { immediate: true })
onUnmounted(() => { stopTick(); if (io) io.disconnect() })

/* ---- 转换（仅纯待办可升级） ---- */
async function doConvert(type) {
  const t = convertTarget.value
  if (type === 'project') {
    const p = await store.create('projects', { name: t.text, desc: t.note || '', status: 'doing', priority: t.priority }, { silent: true })
    await store.create('todos', { text: t.text, date: today(), status: 'done', doneDate: today(), priority: t.priority, projectId: p.id, convertedTo: { type: 'project', id: p.id } }, { silent: true })
  } else {
    const act = await store.create('actions', { name: t.text, desc: t.note || '', status: 'todo', priority: t.priority, startDate: t.date || today() }, { silent: true })
    await store.create('todos', { text: t.text, date: today(), status: 'done', doneDate: today(), priority: t.priority, actionId: act.id, convertedTo: { type: 'action', id: act.id } }, { silent: true })
  }
  await store.remove('todos', t.id, { silent: true })
  showConvert.value = false
  store.toast(`已升级为${type === 'project' ? '项目' : '行动'}`)
}

/* ---- 删除（联动确认） ---- */
async function delTodo(t) {
  const extra = t.actionId ? '（该待办绑定行动，将一并删除）' : ''
  if (!confirm(`删除待办「${t.text}」？${extra}`)) return
  await store.removeTodo(t)
}
function openDetail(todo) {
  const d = { ...todo }
  const a = actOf(d)
  const rep = isRepeat(a) ? a.repeat : null
  dWasRep.value = !!(rep && rep.type)
  dOrigEnd.value = d.endDate || ''
  // 回填当前重复规则（若绑定行动有重复）
  dRepType.value = (rep && rep.type) || ''
  dWeek.value = Array.isArray(rep?.weekdays) && rep.weekdays.length ? [...rep.weekdays] : [1, 3, 5]
  dDaysText.value = Array.isArray(rep?.days) && rep.days.length ? rep.days.join(',') : '1,15'
  // 历史/种子脏数据归一：单日 todo 的 endDate 与 date 相同并非真实跨日 → 打开时按"未填结束"显示
  if (!dRepType.value && d.endDate && d.endDate <= (d.startDate || d.date)) d.endDate = ''
  detailTodo.value = d
  showDetail.value = true
}
// 重复表单（与职业发展行动编辑一致：类型 + 星期 chips / 每月日期）
const dRepType = ref('')
const dWeek = ref([1, 3, 5])
const dDaysText = ref('1,15')
const dWasRep = ref(false) // 打开时原本就是重复待办（用于取消重复时清理旧的重复终止日）
const dOrigEnd = ref('')   // 打开时的原始结束日期
const dHasRep = computed(() => !!dRepType.value)
const dRepObj = computed(() => {
  const t = dRepType.value
  if (t === 'weekly') return { type: 'weekly', weekdays: [...dWeek.value].sort((a, b) => a - b) }
  if (t === 'monthly') return { type: 'monthly', days: dDaysText.value.split(/[,，\s]+/).map(Number).filter(n => n >= 1 && n <= 31) }
  if (t) return { type: t }
  return null
})
// 预览：当前规则自起始日起命中的日期
const dPreview = computed(() => {
  const rep = dRepObj.value
  const base = detailTodo.value
  if (!rep || !base) return []
  const startRaw = base.date || base.startDate || today()
  const start = new Date(startRaw + 'T00:00:00')
  const hardEnd = new Date(start); hardEnd.setDate(hardEnd.getDate() + 90)
  const stopRaw = base.endDate
  const stop = stopRaw ? new Date(Math.min(new Date(stopRaw + 'T00:00:00'), hardEnd)) : hardEnd
  const out = []
  const probe = { repeat: rep }
  for (let dd = new Date(start); dd <= stop && out.length < 8; dd.setDate(dd.getDate() + 1)) {
    const fs = fmtDate(dd)
    if (repeatHits(probe, fs)) out.push(fs)
  }
  return out
})
async function saveDetail() {
  const d = detailTodo.value
  const rep = dRepObj.value
  if (rep) {
    if (rep.type === 'weekly' && !rep.weekdays.length) { store.toast('请至少选择一个星期', 'err'); return }
    if (rep.type === 'monthly' && !rep.days.length) { store.toast('请填写每月日期（如 1,15）', 'err'); return }
    // 重复待办：date 即生效起点（开始日期）
    d.startDate = d.date || d.startDate || today()
    if (d.endDate && d.endDate < d.startDate) { store.toast('结束日期不能早于开始日期', 'err'); return }
    if (!d.endDate) d.endDate = null
  } else {
    // 普通待办：date 就是"哪天做"；只有填了结束日期才成为跨日区间（首日 = date）
    if (dWasRep.value && d.endDate === dOrigEnd.value) d.endDate = null // 取消重复：若结束日期没动过（还是原重复终止日）→ 回单日
    if (d.endDate) {
      if (d.endDate < (d.date || d.startDate || today())) { store.toast('结束日期不能早于日期', 'err'); return }
      d.startDate = d.date
    } else {
      d.endDate = null
      d.startDate = d.date
    }
  }
  await store.saveTodoDetail(d, rep, { msg: '已保存' })
  showDetail.value = false
}

/* ---- 完成情况弹窗（与绑定行动双向同步） ---- */
const showCompletion = ref(false)
const completionTarget = ref(null)
const completionText = ref('')
const completionMarkDone = ref(false)
function openCompletion(todo) {
  completionTarget.value = todo
  completionText.value = todo.completionNote || ''
  completionMarkDone.value = false
  showCompletion.value = true
}
async function submitCompletion() {
  const t = completionTarget.value
  if (!t) return
  showCompletion.value = false
  await store.saveCompletion(t, completionText.value, { markDone: completionMarkDone.value })
}

const priCls = (p) => p === 'P1' || p === 'high' ? 'red' : (p === 'P2' || p === 'medium' ? 'orange' : 'gray')
const repeatLabel = (r) => r && r.type ? (r.type === 'daily' ? '每日' : r.type === 'weekly' ? '每周' : '每月') : ''
</script>

<template>
  <div class="main-inner" ref="scrollEl">
    <div class="page-head">
      <div>
        <div class="page-title">todo</div>
        <div class="page-sub">待办与行动互相同步。共 {{ store.todos.length }} 条，{{ store.openTodos.length }} 条待办。</div>
      </div>
      <div class="flex gap-8">
        <div class="seg">
          <button class="seg-btn" :class="{ on: view === 'list' }" @click="view = 'list'">列表</button>
          <button class="seg-btn" :class="{ on: view === 'calendar' }" @click="view = 'calendar'">日历</button>
        </div>
        <button class="btn primary" @click="showAdd = true; quickAdd = false">＋ 添加待办</button>
      </div>
    </div>

    <!-- 统一创建弹窗（单日 / 跨日 / 重复 同一入口） -->
    <Modal v-if="showAdd" title="添加待办" @close="showAdd = false">
      <label class="field"><span>内容</span><input v-model="addForm.text" class="input" placeholder="要做什么？" autofocus @keydown.enter="submitAdd" /></label>
      <div class="grid-2">
        <label class="field"><span>{{ addForm.repeatType ? '开始日期（生效起）' : '日期' }}</span><input v-model="addForm.date" type="date" class="input" /></label>
        <label class="field"><span>{{ addForm.repeatType ? '结束日期（可空 = 一直有效）' : '结束日期（可选）' }}</span><input v-model="addForm.endDate" type="date" class="input" :min="addForm.date" /></label>
      </div>
      <!-- 两种场景语义不同，说明常驻并随重复类型切换（此前只在非重复时显示，选了重复反而没有提示） -->
      <p class="muted" style="font-size:12px;margin-top:-4px">{{ addForm.repeatType ? '从开始日期起按规则重复出现；不填结束 = 一直有效，填了 = 到那天为止。' : '不填结束 = 只在这天做；填了结束日期 = 跨日区间，区间内每天都会显示这条待办。' }}</p>
      <div class="grid-2">
        <label class="field"><span>重复类型</span>
          <select v-model="addForm.repeatType" class="select">
            <option value="">不重复</option>
            <option value="daily">每日</option>
            <option value="weekly">每周</option>
            <option value="monthly">每月</option>
          </select>
        </label>
        <label class="field"><span>优先级</span>
          <select v-model="addForm.priority" class="select">
            <option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option>
          </select>
        </label>
      </div>
      <div v-if="addForm.repeatType === 'weekly'" class="field">
        <span>星期</span>
        <div class="flex">
          <label v-for="(w, i) in ['日','一','二','三','四','五','六']" :key="w" class="wd-chip" :class="{ on: addForm.weekdays.includes(i) }"
                 @click="addForm.weekdays.includes(i) ? addForm.weekdays = addForm.weekdays.filter(x => x !== i) : addForm.weekdays.push(i)">{{ w }}</label>
        </div>
      </div>
      <label v-if="addForm.repeatType === 'monthly'" class="field"><span>每月日期（逗号分隔）</span><input v-model="addForm.daysText" class="input" placeholder="如 1,15" /></label>
      <label class="field"><span>所属项目（可选）</span>
        <select v-model="addForm.projectId" class="select" @change="addForm.taskId = ''">
          <option value="">不绑定（纯待办）</option>
          <option v-for="p in store.projects" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>
      <label class="field" v-if="addForm.projectId"><span>所属任务（可选）</span>
        <select v-model="addForm.taskId" class="select">
          <option value="">仅绑定项目</option>
          <option v-for="t in tasksOf(addForm.projectId)" :key="t.id" :value="t.id">{{ t.name }}</option>
        </select>
      </label>
      <div v-if="addPreview.length" class="preview-box">
        <div class="muted mb-8">{{ addForm.repeatType === 'daily' ? '每日' : addForm.repeatType === 'weekly' ? '每周' : '每月' }}{{ addForm.endDate ? `（至 ${addForm.endDate}）` : '（持续）' }}，最近命中：</div>
        <div class="flex gap-4" style="flex-wrap:wrap">
          <span v-for="d in addPreview" :key="d" class="tag">{{ d }}</span>
        </div>
      </div>
      <p class="muted" style="font-size:12px">绑定项目/任务后，将自动在「进行中」创建对应行动并保持同步（勾选、番茄钟双向联动）。</p>
      <template #foot>
        <button class="btn" @click="showAdd = false">取消</button>
        <button class="btn primary" @click="submitAdd">创建</button>
      </template>
    </Modal>

    <!-- 快速添加条 -->
    <div class="card mb-16" v-if="quickAdd && !showAdd">
      <form class="flex" @submit.prevent="store.addTodo({ text: newText, date: today(), status: 'todo', priority: 'P3' }).then(() => { newText = ''; quickAdd = false })">
        <input v-model="newText" class="input grow" placeholder="输入待办内容，回车快速创建…" autofocus />
        <button class="btn primary" type="submit">创建</button>
      </form>
    </div>
    <div class="card mb-16" v-else-if="!quickAdd && !showAdd">
      <div class="flex" @click="quickAdd = true" style="cursor:text">
        <span style="color:var(--text-3)">＋ 添加待办…</span>
      </div>
    </div>

    <!-- 列表视图 -->
    <template v-if="view === 'list'">
      <div v-for="g in shownGroups" :key="g.label" class="mb-16 group-drop" :class="{ 'drop-target': hoverGroup === g.label }"
           @dragover.prevent="hoverGroup = g.label" @dragleave="hoverGroup === g.label && (hoverGroup = '')" @drop.prevent="dropOnGroup(g, $event)">
        <div class="group-head">
          <span>{{ g.label }}</span>
          <span v-if="!g.isToday && !g.isUnassigned" class="muted" style="font-size:12px;font-weight:400">{{ g.date }}</span>
          <span class="muted">{{ g.list.filter(r => r.done).length }}/{{ g.list.length }}</span>
        </div>
        <div class="card" style="padding:6px 10px">
          <div v-for="r in g.list" :key="r.todo.id" class="todo-block">
          <div class="todo-row"
               :draggable="canDrag(r.todo) && renamingId !== r.todo.id" :class="{ dragging: drag && drag.id === r.todo.id }"
               :title="canDrag(r.todo) ? '拖拽可移动到其他日期' : '重复行动按规则出现，不支持拖拽'"
               @dragstart="canDrag(r.todo) && renamingId !== r.todo.id && dragStart(r.todo, $event)" @dragend="dragEnd">
            <div class="checkbox" :class="{ on: r.done }" @click="toggleRow(r)">✓</div>
            <div class="grow" style="min-width:0" :style="{ textDecoration: r.done ? 'line-through' : 'none', color: r.done ? 'var(--text-3)' : 'inherit' }">
              <input v-if="renamingId === r.todo.id" :ref="renameRef" v-model="renameText" class="rename-input"
                     @keydown.enter.prevent="commitRename(r.todo.id)" @keydown.esc.prevent="cancelRename"
                     @blur="commitRename(r.todo.id)" />
              <div v-else class="truncate" title="双击可改名" @dblclick.stop="startRename(r.todo.id, r.todo.text)">{{ r.todo.text }}</div>
              <div class="flex gap-4" style="margin-top:2px">
                <span v-if="r.todo.projectId" class="tag gray xs">{{ store.projectMap[r.todo.projectId]?.name }}</span>
                <span v-if="r.todo.taskId && store.taskMap[r.todo.taskId]" class="tag primary xs">{{ store.taskMap[r.todo.taskId]?.name }}</span>
                <span v-if="rowRepeatLabel(r.todo)" class="tag cyan xs">↻ {{ rowRepeatLabel(r.todo) }}</span>
                <span v-if="r.todo.actionId" class="tag green xs">行动</span>
              </div>
            </div>
            <span v-if="(r.todo.pomoCount || 0) > 0 || (r.todo.pomoEstimate || 0) > 0 || r.todo.actionId" class="pomo-cnt" title="已完成番茄/番茄估算">🍅{{ r.todo.pomoCount || 0 }}/{{ r.todo.pomoEstimate || 0 }}</span>
            <button v-if="runningId !== r.todo.id" class="btn sm pomo-btn" :title="store.pomoSession.endAt ? '已有番茄钟进行中，请先结束' : `开始 ${pomoMin} 分钟番茄钟`" @click="startTodoTimer(r.todo)">🍅 专注</button>
            <button v-else class="btn sm danger pomo-btn" title="结束番茄钟" @click="stopTodoTimer">■ {{ fmt(timerLeft) }}</button>
            <button class="btn sm sub-btn" :class="{ on: ckTotal(r.todo) > 0 && ckDone(r.todo) === ckTotal(r.todo) }"
                    :title="ckTotal(r.todo) ? `子项 ${ckDone(r.todo)}/${ckTotal(r.todo)}（点击展开/收起）` : '把这条待办拆成多个子项'"
                    @click="toggleChecklist(r.todo)">☑<span v-if="ckTotal(r.todo)"> {{ ckDone(r.todo) }}/{{ ckTotal(r.todo) }}</span></button>
            <span class="tag" :class="priCls(r.todo.priority)">{{ r.todo.priority }}</span>
            <button class="icon-btn completion-btn" :class="{ on: r.todo.completionNote }"
                    :title="r.todo.completionNote ? '完成情况：' + r.todo.completionNote + '（点击编辑）' : '填写完成情况'"
                    @click="openCompletion(r.todo)">📝</button>
            <button v-if="!r.todo.actionId" class="icon-btn" title="升级" @click="convertTarget = r.todo; showConvert = true">↗</button>
            <button class="icon-btn" title="详情" @click="openDetail(r.todo)">⋯</button>
            <button class="icon-btn" title="删除" @click="delTodo(r.todo)">✕</button>
          </div>
          <Checklist v-if="ckShown(r.todo)" :action-id="r.todo.actionId || ''" :todo-id="r.todo.id" />
          </div>
          <div v-if="!g.list.length" class="empty" style="padding:16px">空</div>
        </div>
      </div>
      <!-- 底部放置区：目标日期尚无分组时，拖到此处选择日期 -->
      <div class="drop-new" :class="{ over: hoverNew }"
           @dragover.prevent="hoverNew = true" @dragleave="hoverNew = false" @drop.prevent="dropToNew($event)">
        ⇄ 拖到此处 → 移到其他日期…
      </div>
      <!-- 分批加载：滚动到底自动追加，另有"加载更多"按钮兜底 -->
      <div v-if="visibleCount < groups.length" ref="sentinel" class="list-loading" @click="loadMore">↓ 加载更多历史…</div>
      <div v-else-if="groups.length > PAGE" class="list-end">已显示全部（共 {{ groups.length }} 组）</div>
    </template>

    <!-- 日历视图 -->
    <template v-else>
      <div class="card">
        <div class="flex-between mb-12">
          <button class="btn ghost sm" @click="calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() - 1, 1)">‹</button>
          <span style="font-weight:600">{{ calTitle }}</span>
          <button class="btn ghost sm" @click="calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() + 1, 1)">›</button>
        </div>
        <div class="cal-grid">
          <div v-for="w in ['日','一','二','三','四','五','六']" :key="w" class="cal-wd">{{ w }}</div>
          <div v-for="d in calDays" :key="d.f" class="cal-day"
               :class="{ 'out-month': !d.inMonth, today: d.f === today(), 'drop-target': hoverDay === d.f }"
               @dragover.prevent="hoverDay = d.f" @dragleave="hoverDay === d.f && (hoverDay = '')" @drop.prevent="dropOnDay(d, $event)">
            <div class="cal-num">{{ d.day }}</div>
            <div v-for="c in calShown(d)" :key="c.f + '-' + c.todo.id" class="cal-todo"
                 :class="{ done: c.done, dragging: drag && drag.id === c.todo.id }"
                 :draggable="!c.repeat"
                 :title="(c.repeat ? '重复行动（按规则出现，不可拖拽）。' + (c.done ? '已完成（' + c.f + '），点击取消' : '未完成，点击完成当天') + (c.f > today() ? '（未来日期不可提前完成）' : '') : c.todo.text + (c.todo.completionNote ? '\n完成情况：' + c.todo.completionNote : '') + '（可拖拽到其他日期）')"
                 @dragstart="!c.repeat && dragStart(c.todo, $event)" @dragend="dragEnd"
                 @click="clickCalCell(c)">{{ c.repeat ? '↻ ' : '' }}{{ c.todo.text }}</div>
            <div v-if="d.todos.length > 3" class="cal-more" @click.stop="toggleDayExpand(d.f)"
                 :title="isDayExpanded(d.f) ? '收起' : '展开全部'">{{ isDayExpanded(d.f) ? '▲ 收起' : `+${d.todos.length - 3} 更多 ▾` }}</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 转换弹窗 -->
    <Modal v-if="showConvert" :title="`升级「${convertTarget.text}」`" @close="showConvert = false">
      <p class="muted mb-12">把一条待办升级为更结构化的对象，原待办将自动完成归档。</p>
      <div class="grid-2">
        <button class="btn big-opt" @click="doConvert('project')"><div style="font-size:22px">🗂</div><div>升级为项目</div><div class="muted">需要多步骤推进的事</div></button>
        <button class="btn big-opt" @click="doConvert('action')"><div style="font-size:22px">⚡</div><div>升级为行动</div><div class="muted">挂到项目下的具体行动</div></button>
      </div>
      <template #foot><button class="btn" @click="showConvert = false">取消</button></template>
    </Modal>

    <!-- 详情弹窗（编辑单条 todo：日期 / 跨日 / 重复规则均可改，与行动编辑一致） -->
    <Modal v-if="showDetail" :title="detailTodo.text" @close="showDetail = false">
      <div class="flex gap-8 mb-16" style="flex-wrap:wrap">
        <span class="tag" :class="priCls(detailTodo.priority)">{{ detailTodo.priority }}</span>
        <span class="tag" :class="dHasRep ? 'cyan' : 'gray'">{{ dHasRep ? '↻ 重复待办（按规则出现）' : detailTodo.date }}</span>
        <span v-if="detailTodo.projectId" class="tag primary">项目：{{ store.projectMap[detailTodo.projectId]?.name }}</span>
        <span v-if="detailTodo.taskId && store.taskMap[detailTodo.taskId]" class="tag cyan">任务：{{ store.taskMap[detailTodo.taskId]?.name }}</span>
        <span v-if="detailTodo.actionId" class="tag green">已绑定行动（自动同步）</span>
      </div>
      <label class="field"><span>备注</span><textarea v-model="detailTodo.note" class="textarea" rows="4"></textarea></label>
      <div class="grid-2">
        <label class="field"><span>{{ dHasRep ? '开始日期（生效起）' : '日期' }}</span><input v-model="detailTodo.date" type="date" class="input" /></label>
        <label class="field"><span>番茄估算</span><input v-model.number="detailTodo.pomoEstimate" type="number" class="input" /></label>
      </div>
      <label class="field"><span>{{ dHasRep ? '结束日期（可空 = 一直有效）' : '结束日期（可选，填了即为跨日）' }}</span><input v-model="detailTodo.endDate" type="date" class="input" :min="detailTodo.date" /></label>
      <p v-if="!dHasRep" class="muted" style="font-size:12px;margin-top:-6px">不填结束 = 只在这天做；填了结束日期 = 跨日区间，区间内每天都会显示。</p>
      <div class="grid-2" style="margin-top:10px">
        <label class="field"><span>优先级</span>
          <select v-model="detailTodo.priority" class="select">
            <option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option>
          </select>
        </label>
        <label class="field"><span>重复类型</span>
          <select v-model="dRepType" class="select">
            <option value="">不重复</option>
            <option value="daily">每日</option>
            <option value="weekly">每周</option>
            <option value="monthly">每月</option>
          </select>
        </label>
      </div>
      <div v-if="dRepType === 'weekly'" class="field">
        <span>星期</span>
        <div class="flex">
          <label v-for="(w, i) in ['日','一','二','三','四','五','六']" :key="w" class="wd-chip" :class="{ on: dWeek.includes(i) }"
                 @click="dWeek.includes(i) ? dWeek = dWeek.filter(x => x !== i) : dWeek.push(i)">{{ w }}</label>
        </div>
      </div>
      <label v-if="dRepType === 'monthly'" class="field"><span>每月日期（逗号分隔）</span><input v-model="dDaysText" class="input" placeholder="如 1,15" /></label>
      <div v-if="dPreview.length" class="preview-box">
        <div class="muted mb-8">最近命中：</div>
        <div class="flex gap-4" style="flex-wrap:wrap">
          <span v-for="d in dPreview" :key="d" class="tag">{{ d }}</span>
        </div>
      </div>
      <p v-if="dHasRep" class="muted" style="font-size:12px;margin-top:-6px">设置了重复后：待办按规则在日历自动展开、完成按天记录；已绑定行动或纯待办升级时会同步到「进行中」。</p>
      <template #foot>
        <button class="btn" @click="showDetail = false">取消</button>
        <button class="btn primary" @click="saveDetail">保存</button>
      </template>
    </Modal>

    <!-- 拖拽改期弹窗（拖到底部"其他日期"区域触发） -->
    <Modal v-if="showMove" title="移动到指定日期" @close="showMove = false">
      <p class="muted mb-12 truncate">「{{ moveTarget?.text }}」</p>
      <label class="field"><span>目标日期</span><input v-model="moveDate" type="date" class="input" autofocus @keydown.enter="confirmMove" /></label>
      <template #foot>
        <button class="btn" @click="showMove = false">取消</button>
        <button class="btn primary" @click="confirmMove">移动</button>
      </template>
    </Modal>

    <!-- 完成情况弹窗 -->
    <Modal v-if="showCompletion" title="完成情况" @close="showCompletion = false">
      <p class="muted mb-12 truncate">「{{ completionTarget?.text }}」</p>
      <label class="field"><span>完成情况</span>
        <textarea v-model="completionText" class="textarea" rows="5" autofocus
                  placeholder="这次做得怎么样？结果如何？有什么值得记录的…"></textarea>
      </label>
      <label class="flex gap-8" style="font-size:13px;cursor:pointer">
        <input type="checkbox" v-model="completionMarkDone" />
        <span>同时标记为已完成</span>
      </label>
      <p v-if="completionTarget?.actionId" class="muted" style="font-size:12px">该待办绑定了行动，完成情况将同步过去。</p>
      <template #foot>
        <button class="btn" @click="showCompletion = false">取消</button>
        <button class="btn primary" @click="submitCompletion">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.seg-btn { border: none; background: transparent; padding: 5px 14px; border-radius: 8px; font-size: 13px; color: var(--text-2); cursor: pointer; }
.seg-btn.on { background: var(--surface); color: var(--primary); font-weight: 600; box-shadow: var(--shadow-sm); }

.group-head { display: flex; align-items: baseline; gap: 8px; padding: 0 4px 8px; font-weight: 600; font-size: 14px; }
.todo-row { display: flex; align-items: center; gap: 10px; padding: 7px 6px; border-radius: 8px; }
.todo-row:hover { background: var(--surface-2); }

/* ---- 拖拽改期 ---- */
.todo-row[draggable="true"] { cursor: grab; }
.todo-row.dragging, .cal-todo.dragging { opacity: .35; }
.group-drop { border-radius: 12px; }
.group-drop.drop-target { outline: 2px dashed var(--primary); outline-offset: 2px; background: var(--primary-soft); }
.drop-new {
  border: 2px dashed var(--border-strong); border-radius: 10px; text-align: center;
  padding: 14px; color: var(--text-3); font-size: 13px; transition: all .12s;
}
.drop-new.over { border-color: var(--primary); color: var(--primary); background: var(--primary-soft); }
.list-loading {
  text-align: center; padding: 12px 0; font-size: 13px; color: var(--text-3); cursor: pointer;
  border-radius: 10px; user-select: none;
}
.list-loading:hover { color: var(--primary); background: var(--surface-2); }
.list-end { text-align: center; padding: 12px 0; font-size: 12px; color: var(--text-3); }
.cal-todo[draggable="true"] { cursor: grab; }
.cal-day.drop-target { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-soft) inset; background: var(--primary-soft); }

.pomo-cnt { font-size: 12px; color: var(--primary); font-weight: 600; white-space: nowrap; }
.pomo-btn { padding: 3px 9px; font-size: 12px; white-space: nowrap; }
/* 子项开关：与番茄按钮同尺寸，全部完成时转绿 */
.sub-btn { padding: 3px 9px; font-size: 12px; white-space: nowrap; }
.sub-btn.on { color: #2a8f5e; border-color: #a8dbc2; }
.tag.xs { font-size: 10px; padding: 0 6px; }
.tag.green { background: #e7f6ee; color: #2a8f5e; }
.completion-btn.on { background: var(--primary-soft); border-radius: 6px; }

.cal-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; }
.cal-wd { text-align: center; font-size: 12px; color: var(--text-3); padding: 4px 0; }
.cal-day { border: 1px solid var(--border); border-radius: 10px; min-height: 76px; padding: 5px; background: var(--surface); }
.cal-day.out-month { opacity: .35; }
.cal-num { font-size: 12px; font-weight: 600; margin-bottom: 3px; }
.cal-todo {
  font-size: 11px; padding: 1px 5px; border-radius: 5px; background: var(--primary-soft); color: var(--primary);
  margin-bottom: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer;
}
/* 已完成：灰色底 + 灰色删除线；未完成：主题色 */
.cal-todo.done { background: var(--surface-2); color: var(--text-3); text-decoration: line-through; }
.cal-more {
  font-size: 10px; padding: 1px 4px; color: var(--text-3); cursor: pointer;
  border-radius: 5px; user-select: none;
}
.cal-more:hover { color: var(--primary); background: var(--primary-soft); }

.wd-chip { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--border-strong); cursor: pointer; font-size: 12px; }
.wd-chip.on { background: var(--primary); color: #fff; border-color: var(--primary); }

.preview-box { background: var(--surface-2); border-radius: 10px; padding: 12px; margin-top: 4px; }
.big-opt { flex-direction: column; gap: 4px; padding: 18px 12px; height: auto; }
</style>
