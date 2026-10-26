<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { useDataStore, today, fmtDate, todoHitsDay, repeatHits, repeatDoneOn, isRepeat, checklistItemsOf, checklistParentOf, progressOf, todoProgressOf, isTodoShelved } from '../stores/data'
import { useInlineRename } from '../composables/useInlineRename'
import Modal from '../components/Modal.vue'
import Checklist from '../components/Checklist.vue'
import ProgressBar from '../components/ProgressBar.vue'
import ProgressModal from '../components/ProgressModal.vue'
import StatPill from '../components/StatPill.vue'

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
/* 搁置判定：绑定行动的看行动上的标记，纯待办看自己（续办提醒跟着源条目走）——
   列表分组、日历、搁置区都用这一个口径，避免"收起来一半" */
const shelved = (todo) => isTodoShelved(todo, store.actions, store.todos)

/* ---- 行/格状态：重复 todo 的"当日实例"完成态以 action.doneDates 为准（按天） ---- */
const actOf = (todo) => (todo.actionId ? actMap.value[todo.actionId] : null)
/* 进度记在行动侧，待办行只读取展示：
   - 投影待办（actionId）→ 绑定行动
   - 续办提醒（fromActionId）→ 来源行动，这样「继续推进」那行也能看到当前推进到哪 */
const actSrc = (todo) => actOf(todo) || (todo && todo.fromActionId ? actMap.value[todo.fromActionId] : null)
// 纯待办进度续办提醒（fromTodoId）→ 源待办
const todoMap = computed(() => Object.fromEntries(store.todos.map(t => [t.id, t])))
const carryTodoOf = (todo) => (todo && todo.fromTodoId ? todoMap.value[todo.fromTodoId] || null : null)

/* ---- 筛选（按项目 / 只看未完成）----
   项目归属以「行动」为真相：投影待办、行动续办提醒都看背后行动，纯待办看自己。
   这样在「进行中」给行动换了项目，待办侧的筛选结果会跟着走，不会两边对不上。 */
const NO_PROJECT = '__none__' // 「未绑定项目」的筛选值（区别于「全部」的空字符串）
const filterProject = ref('')
const onlyOpen = ref(false)
const projectOf = (todo) => {
  const act = actSrc(todo)
  const src = carryTodoOf(todo)
  return (act && act.projectId) || (todo && todo.projectId) || (src && src.projectId) || ''
}
const matchFilter = (todo) => {
  if (!filterProject.value) return true
  const pid = projectOf(todo)
  return filterProject.value === NO_PROJECT ? !pid : pid === filterProject.value
}
const hasFilter = computed(() => !!filterProject.value || onlyOpen.value)
// 下拉里的项目按名称排（区域→项目的层级在「进行中」看，这里只做扁平选择）
const projectOptions = computed(() =>
  [...store.projects].sort((a, b) => (a.name || '').localeCompare(b.name || '', 'zh')))
// 当前筛选下的未完成条数（与 openTodos 同口径：不含已搁置、不含已完成）
const filteredOpen = computed(() => store.openTodos.filter(matchFilter).length)
function clearFilter() { filterProject.value = ''; onlyOpen.value = false }
const filterLabel = computed(() => {
  if (!filterProject.value) return ''
  if (filterProject.value === NO_PROJECT) return '未绑定项目'
  return store.projectMap[filterProject.value]?.name || '已删除的项目'
})
// 筛选落在具体项目上时，新建的待办默认归到该项目——否则创建完立刻被筛选隐藏，看起来像"没加上"
const filterProjectId = computed(() => (filterProject.value && filterProject.value !== NO_PROJECT ? filterProject.value : null))
function openAdd() {
  addForm.value = { ...addForm.value, projectId: filterProjectId.value || '', taskId: '' }
  showAdd.value = true
  quickAdd.value = false
}
// 快速添加：同样跟着项目筛选走（「未绑定项目」筛选下保持纯待办）
async function quickSubmit() {
  const text = newText.value.trim()
  if (!text) return
  await store.addTodo({ text, date: today(), status: 'todo', priority: 'P3', projectId: filterProjectId.value })
  newText.value = ''
  quickAdd.value = false
}
/* 一条待办的进度：
   - 投影待办 / 行动续办提醒 → 看它背后的行动
   - 纯待办的续办提醒 → 看源待办（共享同一份进度百分比）
   - 纯待办 → 看自己的 progress（在「待办」里直接记）
   进度只看 progress 字段本身，不因「勾选完成」就硬抬到 100%——
   勾完成只表示今天做了这一轮，真正的百分比仍由 progress 决定。 */
const todoProg = (todo) => {
  const act = actSrc(todo)
  if (act) return progressOf(act)
  const src = carryTodoOf(todo)
  if (src) return todoProgressOf(src)
  return todoProgressOf(todo)
}
/* 完成情况与进度同源：只存一份，跟着进度的归属走——
   投影待办 / 行动续办提醒 → 行动上的那段话；纯待办的续办提醒 → 源待办；纯待办 → 自己 */
const todoNote = (todo) => {
  const act = actSrc(todo)
  if (act) return act.completionNote || ''
  const src = carryTodoOf(todo)
  if (src) return src.completionNote || ''
  return (todo && todo.completionNote) || ''
}
// 记录进度弹窗：传待办本身，绑定了行动的在弹窗里自动转给行动（与「进行中」同一套口径：填最终值）
const showProg = ref(false)
const progTarget = ref(null)
function openTodoProgress(todo) {
  if (!todo) return
  progTarget.value = todo
  showProg.value = true
}
// 详情弹窗里记进度：先关详情再开进度弹窗（避免两个弹窗叠在一起）
function openProgressFromDetail() {
  if (!detailTodo.value) return
  // 详情里是副本，进度要记到 store 里的真身
  progTarget.value = store.todos.find(t => t.id === detailTodo.value.id) || detailTodo.value
  showDetail.value = false
  showProg.value = true
}
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
    if (onlyOpen.value && done) return // 「只看未完成」按行过滤：重复待办按当天实例的完成态判断
    const day = winMap[f] || { f, label: f, isToday: f === t }
    if (!gmap[f]) { gmap[f] = { label: day.label, date: f, isToday: day.isToday, list: [] }; g.push(gmap[f]) }
    gmap[f].list.push({ todo, done, repeat: isRep, day: f })
  }
  for (const todo of store.todos) {
    if (shelved(todo) || !matchFilter(todo)) continue // 搁置的不进列表；不符合项目筛选的也不进
    const a = actOf(todo)
    if (isRepeat(a)) continue // 重复待办下面按窗口逐日展开
    if (!todo.date) {
      // 未分配：行动创建时未填开始时间 → 待办没有 date 锚点 → 单独成组 + 置顶
      if (onlyOpen.value && todo.status === 'done') continue
      unassigned.push({ todo, done: todo.status === 'done', repeat: false, day: UNASSIGNED_KEY })
      continue
    }
    if (todo.date > horizon) continue
    push(todo.date, todo, todo.status === 'done', false)
  }
  for (const day of listWindow.value) {
    for (const todo of store.todos) {
      if (shelved(todo) || !matchFilter(todo)) continue
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
   绑定行动的待办与行动共用同一份子项；「继续推进」这类续办提醒也算绑定
   （fromActionId），一并挂到背后行动的子项上 —— 子项跟着行动长期存在，勾选状态
   在「进行中」和「待办」两边是同一份，不会因为续办而重置。 */
const ckOpen = ref({})
/* 待办 → 子项归属范围：
   - 投影待办（actionId）→ 背后行动 + 自己
   - 续办提醒（fromActionId）→ 背后行动 + 行动投影待办（早期子项可能落在 todo 侧）+ 自己
   - 纯待办的续办提醒（fromTodoId）→ 源待办（checklistParentOf 已把 todoId 指回源），读写在同一份
   于是「继续推进」那条展开的就是行动现有的子项，完成状态原样保留，勾选两边同步。 */
const ckParent = (todo) => {
  const p = checklistParentOf(todo)
  if (!todo.actionId && p.actionId) {
    const proj = store.todos.find(t => t.actionId === p.actionId)
    if (proj && proj.id !== todo.id) p.todoIds = [proj.id]
  }
  return p
}
const ckItemsOf = (todo) => checklistItemsOf(store.checklist, ckParent(todo))
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
// 换筛选条件后回到第一批：否则可能停在上一次滚动到的批次，看起来像「筛出来是空的」
watch([filterProject, onlyOpen], () => { visibleCount.value = PAGE })
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
      if (shelved(todo) || !matchFilter(todo)) continue // 搁置 / 项目筛选：与列表同一口径
      const a = actOf(todo)
      if (isRepeat(a)) {
        // 重复行动：规则命中且处于生效区间才显示当天实例；完成态 = doneDates 含该日
        if (!repeatHits(a, f)) continue
        if (todo.startDate && f < todo.startDate) continue
        if (todo.endDate && f > todo.endDate) continue
        const done = repeatDoneOn(a, f)
        if (onlyOpen.value && done) continue // 「只看未完成」按当天的实例态过滤
        cells.push({ todo, act: a, f, repeat: true, done })
        continue
      }
      // 非重复：跨日必须同时有 startDate + endDate（缺一边都按单日锚定 date，避免"有开始无结束"被当成无限区间满月漂浮）
      const span = todo.startDate && todo.endDate && todo.endDate >= todo.startDate
      const inDay = span
        ? (todo.startDate <= f && todo.endDate >= f)
        : (todo.date === f)
      if (inDay && !(onlyOpen.value && todo.status === 'done')) cells.push({ todo, act: null, f, repeat: false, done: todo.status === 'done' })
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

/* ---- 手机端日历：格子里的文字注定只能显示 2 个字（7 列 × 390px ≈ 45px/格），
   改为「日期 + 圆点」表示有无待办，选中某天后在网格下方给出完整清单。
   两者都由媒体查询控制显隐，桌面端的文字条目与拖拽行为完全不变。 ---- */
const calSel = ref(today())
const selectCalDay = (d) => { calSel.value = calSel.value === d.f ? '' : d.f }
const calSelDay = computed(() => calDays.value.find(d => d.f === calSel.value) || null)
const calSelTodos = computed(() => calSelDay.value ? calSelDay.value.todos : [])
const calSelLabel = computed(() => {
  if (!calSel.value) return ''
  const [y, m, d] = calSel.value.split('-')
  const wd = ['日', '一', '二', '三', '四', '五', '六'][new Date(+y, +m - 1, +d).getDay()]
  return `${+m}月${+d}日 周${wd}`
})

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
  if (!endAt) { stopTick(); return } // 另一端取消/结算了 → 本端停止空转的计时器
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

/* ---- 搁置区（低调入口）----
   搁置 = 暂时不做：从待办列表 / 日历 / 首页今日里收起来，行动本身仍留在项目里。
   入口是列表第一行右侧的灰胶囊，不占额外版面；放回来时选一个具体日期，
   绑定行动的会同步行动的起止日期，所以「进行中」的排期也跟着回来。 */
const shelvedRows = computed(() => store.shelvedTodos)
const showShelved = ref(false)
const shelfDate = ref({}) // 逐条的"放回日期"，打开时默认今天
function openShelved() {
  const m = {}
  for (const t of store.shelvedTodos) m[t.id] = today()
  shelfDate.value = m
  showShelved.value = true
}
async function releaseShelved(t) {
  const d = shelfDate.value[t.id] || today()
  await store.unshelveTodo(t, d)
  if (!store.shelvedTodos.length) showShelved.value = false
}
// 详情弹窗里发起搁置：先关详情，避免弹窗叠着
async function shelveFromDetail() {
  if (!detailTodo.value) return
  const t = store.todos.find(x => x.id === detailTodo.value.id) || detailTodo.value
  showDetail.value = false
  await store.shelveTodo(t)
}

/* ---- 删除（联动确认） ---- */
async function delTodo(t) {
  const extra = t.actionId ? '（该待办绑定行动，将一并删除）' : ''
  if (!confirm(`删除待办「${t.text}」？${extra}`)) return
  await store.removeTodo(t)
}
/* 详情弹窗里发起升级：手机上行内不放「↗ 升级」（操作行会多挤一个图标而多折一行），
   统一收到详情弹窗，能力不减 */
function convertFromDetail() {
  convertTarget.value = detailTodo.value
  showDetail.value = false
  showConvert.value = true
}
function openDetail(todo) {
  const d = { ...todo }
  const a = actOf(d)
  const rep = isRepeat(a) ? a.repeat : null
  dOrigText.value = d.text || ''
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
const dOrigText = ref('')  // 打开时的原始内容（用于判断是否需要同步重命名绑定行动）
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
  // 详情弹窗是触屏设备上唯一的内容修改入口（行内双击改名在手机上不可用），
  // 这里改名要走 renameTodo 而不是直接写字段：绑定行动的待办需要同步行动名，否则两侧会脱钩
  const nextText = (d.text || '').trim()
  if (!nextText) { store.toast('待办内容不能为空', 'err'); return }
  if (nextText !== dOrigText.value) await store.renameTodo(d, nextText, { silent: true })
  d.text = nextText
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
        <button class="btn primary" @click="openAdd">＋ 添加待办</button>
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
      <form class="flex" @submit.prevent="quickSubmit">
        <input v-model="newText" class="input grow" placeholder="输入待办内容，回车快速创建…" autofocus />
        <button class="btn primary" type="submit">创建</button>
      </form>
    </div>
    <div class="card mb-16" v-else-if="!quickAdd && !showAdd">
      <div class="flex" @click="quickAdd = true" style="cursor:text">
        <span style="color:var(--text-3)">＋ 添加待办…</span>
      </div>
    </div>

    <!-- 筛选行：按项目 + 只看未完成（列表与日历同一口径，切视图不丢筛选） -->
    <div class="filter-bar mb-16">
      <label class="filter-field">
        <span class="filter-label">项目</span>
        <select v-model="filterProject" class="select filter-select" aria-label="按项目筛选待办">
          <option value="">全部项目</option>
          <option :value="NO_PROJECT">未绑定项目</option>
          <option v-for="p in projectOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </label>
      <button class="chip" :class="{ on: onlyOpen }" :aria-pressed="onlyOpen" @click="onlyOpen = !onlyOpen">仅看未完成</button>
      <span v-if="hasFilter" class="filter-sum muted">{{ filterLabel || '全部项目' }} · 未完成 {{ filteredOpen }} 条</span>
      <button v-if="hasFilter" class="btn ghost sm filter-clear" @click="clearFilter">清除筛选</button>
    </div>

    <!-- 列表视图 -->
    <template v-if="view === 'list'">
      <div v-for="(g, i) in shownGroups" :key="g.label" class="mb-16 group-drop" :class="{ 'drop-target': hoverGroup === g.label }"
           @dragover.prevent="hoverGroup = g.label" @dragleave="hoverGroup === g.label && (hoverGroup = '')" @drop.prevent="dropOnGroup(g, $event)">
        <div class="group-head">
          <span>{{ g.label }}</span>
          <span v-if="!g.isToday && !g.isUnassigned" class="muted" style="font-size:12px;font-weight:400">{{ g.date }}</span>
          <span class="muted">{{ g.list.filter(r => r.done).length }}/{{ g.list.length }}</span>
          <!-- 搁置区入口：挂在列表第一行（通常是「未分配」）的右侧，不占额外版面，日期再多也不用来回滚 -->
          <button v-if="i === 0" class="shelve-chip" :class="{ on: shelvedRows.length }" title="暂时不做的条目都收在这里，选个日期就能放回待办" @click="openShelved">
            <span>🗄 搁置区</span>
            <span v-if="shelvedRows.length" class="n">{{ shelvedRows.length }}</span>
          </button>
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
                <!-- 行动进度未满 100% 时自动生成的续办提醒（自动维护，不参与行动双向同步） -->
                <span v-if="r.todo.fromActionId || r.todo.fromTodoId" class="tag xs" title="由进度自动生成：每天未满 100% 补一条，满 100% 停止新增（不自动清理）">续办</span>
              </div>
            </div>
            <!-- 操作区独立成组：桌面端紧跟文本、贴行尾（视觉与原先一致）；
                 手机上整体换到第二行，并在自身内部继续换行，避免 10 个控件在
                 375px 宽度里互相挤压（见 scoped 样式的 .row-acts） -->
            <div class="row-acts">
              <span v-if="(r.todo.pomoCount || 0) > 0 || (r.todo.pomoEstimate || 0) > 0 || r.todo.actionId" class="pomo-cnt" title="已完成番茄/番茄估算">🍅{{ r.todo.pomoCount || 0 }}/{{ r.todo.pomoEstimate || 0 }}</span>
              <button v-if="runningId !== r.todo.id" class="btn sm pomo-btn" :title="store.pomoSession.endAt ? '已有番茄钟进行中，请先结束' : `开始 ${pomoMin} 分钟番茄钟`" @click="startTodoTimer(r.todo)">🍅 专注</button>
              <button v-else class="btn sm danger pomo-btn" title="结束番茄钟" @click="stopTodoTimer">■ {{ fmt(timerLeft) }}</button>
              <button class="btn sm sub-btn" :class="{ on: ckTotal(r.todo) > 0 && ckDone(r.todo) === ckTotal(r.todo) }"
                      :title="ckTotal(r.todo) ? `子项 ${ckDone(r.todo)}/${ckTotal(r.todo)}（点击展开/收起）` : '把这条待办拆成多个子项'"
                      @click="toggleChecklist(r.todo)">☑<span v-if="ckTotal(r.todo)"> {{ ckDone(r.todo) }}/{{ ckTotal(r.todo) }}</span></button>
              <span class="tag" :class="priCls(r.todo.priority)">{{ r.todo.priority }}</span>
              <!-- 进度 + 完成情况是同一份内容（同一段话也记在进度里），所以整颗胶囊只有一个入口：
                   非重复 → 记录进度（其中就包含完成情况）；重复条目没有累计进度 → 直接填完成情况 -->
              <StatPill :show-progress="!r.repeat" :pct="todoProg(r.todo)" :note="todoNote(r.todo)"
                        @open="r.repeat ? openCompletion(r.todo) : openTodoProgress(r.todo)" />
              <button v-if="!r.todo.actionId && !r.todo.fromActionId && !r.todo.fromTodoId" class="icon-btn act-upgrade" title="升级" @click="convertTarget = r.todo; showConvert = true">↗</button>
              <button class="icon-btn" title="详情" @click="openDetail(r.todo)">⋯</button>
              <button class="icon-btn" title="删除" @click="delTodo(r.todo)">✕</button>
            </div>
          </div>
          <Checklist v-if="ckShown(r.todo)" :scope="ckParent(r.todo)" />
          </div>
          <div v-if="!g.list.length" class="empty" style="padding:16px">空</div>
        </div>
      </div>
      <!-- 列表为空（或只剩搁置项）时第一行不存在：入口单独占一行右侧，别让搁置区打不开 -->
      <div v-if="!shownGroups.length && hasFilter" class="card filter-empty">
        <div class="muted">没有符合筛选条件的待办</div>
        <button class="btn sm" style="margin-top:10px" @click="clearFilter">清除筛选</button>
      </div>
      <div v-if="!shownGroups.length" class="shelf-bar">
        <button class="shelve-chip" :class="{ on: shelvedRows.length }" title="暂时不做的条目都收在这里，选个日期就能放回待办" @click="openShelved">
          <span>🗄 搁置区</span>
          <span v-if="shelvedRows.length" class="n">{{ shelvedRows.length }}</span>
        </button>
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
      <!-- 日历没有分组行：入口放在日历右上方，位置与列表第一行的胶囊一致 -->
      <div class="shelf-bar">
        <button class="shelve-chip" :class="{ on: shelvedRows.length }" title="暂时不做的条目都收在这里，选个日期就能放回待办" @click="openShelved">
          <span>🗄 搁置区</span>
          <span v-if="shelvedRows.length" class="n">{{ shelvedRows.length }}</span>
        </button>
      </div>
      <div class="card">
        <div class="flex-between mb-12">
          <button class="btn ghost sm cal-nav" @click="calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() - 1, 1)">‹</button>
          <span style="font-weight:600">{{ calTitle }}</span>
          <button class="btn ghost sm cal-nav" @click="calMonth = new Date(calMonth.getFullYear(), calMonth.getMonth() + 1, 1)">›</button>
        </div>
        <div class="cal-grid">
          <div v-for="w in ['日','一','二','三','四','五','六']" :key="w" class="cal-wd">{{ w }}</div>
          <div v-for="d in calDays" :key="d.f" class="cal-day"
               :class="{ 'out-month': !d.inMonth, today: d.f === today(), 'sel-day': d.f === calSel, 'drop-target': hoverDay === d.f }"
               @dragover.prevent="hoverDay = d.f" @dragleave="hoverDay === d.f && (hoverDay = '')" @drop.prevent="dropOnDay(d, $event)"
               @click="selectCalDay(d)">
            <div class="cal-num">{{ d.day }}</div>
            <!-- 手机：圆点概览（最多 3 个 + 溢出计数）。文字条目在 45px 宽的格子里只能
                 露出两个字，所以窄屏改成「点格子 → 看网格下方的完整清单」 -->
            <div class="cal-dots">
              <i v-for="(c, i) in d.todos.slice(0, 3)" :key="i" :class="{ done: c.done }"></i>
              <span v-if="d.todos.length > 3" class="cal-dots-more">+{{ d.todos.length - 3 }}</span>
            </div>
            <!-- 桌面：文字条目（可拖拽改期 / 点击切换完成），窄屏整块隐藏 -->
            <div class="cal-items">
            <div v-for="c in calShown(d)" :key="c.f + '-' + c.todo.id" class="cal-todo"
                 :class="{ done: c.done, dragging: drag && drag.id === c.todo.id }"
                 :draggable="!c.repeat"
                 :title="(c.repeat ? '重复行动（按规则出现，不可拖拽）。' + (c.done ? '已完成（' + c.f + '），点击取消' : '未完成，点击完成当天') + (c.f > today() ? '（未来日期不可提前完成）' : '') : c.todo.text + (todoNote(c.todo) ? '\n完成情况：' + todoNote(c.todo) : '') + '（可拖拽到其他日期）')"
                 @dragstart="!c.repeat && dragStart(c.todo, $event)" @dragend="dragEnd"
                 @click.stop="clickCalCell(c)">{{ c.repeat ? '↻ ' : '' }}{{ c.todo.text }}</div>
              <div v-if="d.todos.length > 3" class="cal-more" @click.stop="toggleDayExpand(d.f)"
                   :title="isDayExpanded(d.f) ? '收起' : '展开全部'">{{ isDayExpanded(d.f) ? '▲ 收起' : `+${d.todos.length - 3} 更多 ▾` }}</div>
            </div>
          </div>
        </div>

        <!-- 手机：选中日的完整清单（桌面端由媒体查询隐藏）。
             点条目即切换完成状态，与格子里点击文字条目是同一个动作。 -->
        <div v-if="calSelDay" class="cal-agenda">
          <div class="cal-agenda-head">
            <span class="cal-agenda-date">{{ calSelLabel }}</span>
            <span class="muted">{{ calSelTodos.filter(c => c.done).length }}/{{ calSelTodos.length }}</span>
          </div>
          <div v-if="!calSelTodos.length" class="cal-agenda-empty muted">这天没有待办</div>
          <div v-for="c in calSelTodos" :key="c.f + '-' + c.todo.id" class="cal-agenda-row" :class="{ done: c.done }"
               @click="clickCalCell(c)">
            <span class="checkbox" :class="{ on: c.done }">✓</span>
            <span class="grow truncate">{{ c.repeat ? '↻ ' : '' }}{{ c.todo.text }}</span>
            <span v-if="todoProg(c.todo) > 0" class="tag xs prog-tag" :title="`当前推进 ${todoProg(c.todo)}%`">📈 {{ todoProg(c.todo) }}%</span>
            <span class="tag" :class="priCls(c.todo.priority)">{{ c.todo.priority }}</span>
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

    <!-- 详情弹窗（编辑单条 todo：内容 / 日期 / 跨日 / 重复规则均可改，与行动编辑一致） -->
    <Modal v-if="showDetail" :title="detailTodo.text" @close="showDetail = false">
      <div class="flex gap-8 mb-16" style="flex-wrap:wrap">
        <span class="tag" :class="priCls(detailTodo.priority)">{{ detailTodo.priority }}</span>
        <span class="tag" :class="dHasRep ? 'cyan' : 'gray'">{{ dHasRep ? '↻ 重复待办（按规则出现）' : detailTodo.date }}</span>
        <span v-if="detailTodo.projectId" class="tag primary">项目：{{ store.projectMap[detailTodo.projectId]?.name }}</span>
        <span v-if="detailTodo.taskId && store.taskMap[detailTodo.taskId]" class="tag cyan">任务：{{ store.taskMap[detailTodo.taskId]?.name }}</span>
        <span v-if="detailTodo.actionId" class="tag green">已绑定行动（自动同步）</span>
        <span v-if="detailTodo.fromActionId" class="tag gray">续办提醒（随行动进度自动维护）</span>
        <span v-if="detailTodo.fromTodoId" class="tag gray">续办提醒（随待办进度自动维护）</span>
      </div>
      <!-- 进度：绑定行动的看行动推进，纯待办记在自己身上；这里能直接看到推进到哪，并就地记一笔
           重复待办按天勾完成，没有累计进度，所以不显示 -->
      <div v-if="!dHasRep" class="prog-detail">
        <div class="flex-between mb-8" style="font-size:12px">
          <span class="muted">{{ actSrc(detailTodo) ? `「${actSrc(detailTodo).name}」` : '这条待办' }}推进 {{ todoProg(detailTodo) }}%，还剩 {{ 100 - todoProg(detailTodo) }}%</span>
          <span class="mono" style="font-weight:700">{{ todoProg(detailTodo) }}%</span>
        </div>
        <ProgressBar :value="todoProg(detailTodo)" />
        <button class="btn sm" style="margin-top:10px" @click="openProgressFromDetail">📈 记录今天的进度</button>
      </div>
      <label class="field"><span>内容</span><input v-model="detailTodo.text" class="input" placeholder="要做什么？" /></label>
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
        <button v-if="!detailTodo.actionId && !detailTodo.fromActionId && !detailTodo.fromTodoId" class="btn" title="升级为项目或行动" @click="convertFromDetail">升级</button>
        <button class="btn" title="暂时不做：从待办里收起来，行动仍留在项目里" @click="shelveFromDetail">搁置</button>
        <button class="btn" @click="showDetail = false">取消</button>
        <button class="btn primary" @click="saveDetail">保存</button>
      </template>
    </Modal>

    <!-- 搁置区：收起来的条目，选日期就能放回待办 -->
    <Modal v-if="showShelved" title="搁置区" @close="showShelved = false">
      <p class="muted mb-12" style="font-size:12px">已从待办和日历里收起，行动仍留在项目里。选一个日期就能放回来。</p>
      <div v-if="!shelvedRows.length" class="empty" style="padding:16px">还没有搁置的条目</div>
      <div v-for="t in shelvedRows" :key="t.id" class="shelf-row">
        <div class="grow" style="min-width:0">
          <div class="truncate">{{ t.text }}</div>
          <div class="flex gap-4" style="margin-top:3px;align-items:center;flex-wrap:wrap">
            <span v-if="t.projectId && store.projectMap[t.projectId]" class="tag gray xs">{{ store.projectMap[t.projectId].name }}</span>
            <span v-if="t.actionId" class="tag green xs">行动</span>
            <span class="muted" style="font-size:11px">搁置于 {{ store.shelveDateOf(t) || '—' }}</span>
          </div>
        </div>
        <div class="shelf-acts">
          <input v-model="shelfDate[t.id]" type="date" class="input shelf-date" title="放回的日期" />
          <button class="btn sm primary" @click="releaseShelved(t)">放出来</button>
          <button class="icon-btn" title="删除" @click="delTodo(t)">✕</button>
        </div>
      </div>
      <template #foot><button class="btn" @click="showShelved = false">关闭</button></template>
    </Modal>

    <!-- 记录行动进度（与「进行中」行动行同一个弹窗：填最终累计值、满 100% 停止新增续办，不自动完成） -->
    <ProgressModal v-if="showProg" :todo="progTarget" @close="showProg = false" />

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

/* ---- 筛选行（项目 + 仅看未完成）：与「媒体」页同一套 chip 观感 ---- */
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-field { display: inline-flex; align-items: center; gap: 8px; min-width: 0; }
.filter-label { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.filter-select { width: auto; min-width: 140px; max-width: 240px; padding-top: 5px; padding-bottom: 5px; }
.chip { border: 1px solid var(--border); background: var(--surface); border-radius: 999px; padding: 6px 15px; font-size: 13px; cursor: pointer; color: var(--text-2); transition: color .13s, background .13s, border-color .13s; }
.chip:hover { border-color: var(--primary-border); color: var(--primary); }
.chip.on { background: var(--primary-soft); border-color: var(--primary-border); color: var(--primary); font-weight: 600; }
.filter-sum { font-size: 12px; }
/* 摘要与「清除」紧跟筛选控件，别被推到屏幕另一头（桌面端看起来像两个不相干的元素） */
.filter-clear { padding-left: 4px; padding-right: 4px; }
.filter-empty { text-align: center; padding: 20px; margin-bottom: 12px; }

.group-head { display: flex; align-items: baseline; gap: 8px; padding: 0 4px 8px; font-weight: 600; font-size: 14px; }
.todo-row { display: flex; align-items: center; gap: 10px; padding: 7px 6px; border-radius: 8px; }
.todo-row:hover { background: var(--surface-2); }
/* 行尾操作区：桌面端与文本同排、紧跟右侧（视觉同重构前）；手机端换行规则见移动端媒体查询 */
.row-acts { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-shrink: 0; }

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
/* 进度标签：既是状态也是入口（点开「记录进度」弹窗）；记过进度加粗 */
.tag.prog-tag { font-size: 11px; font-weight: 600; cursor: pointer; color: var(--primary); background: var(--primary-soft); border: 1px solid var(--primary-border); }
.tag.prog-tag.on { font-weight: 700; }
.tag.prog-tag:hover { color: #fff; background: var(--primary); border-color: var(--primary); }
/* 详情弹窗里的行动进度块 */
.prog-detail { background: var(--surface-2); border-radius: 10px; padding: 12px; margin-bottom: 14px; }

/* 搁置区入口：列表第一行（通常是「未分配」）右侧的灰胶囊；空列表与日历视图用 .shelf-bar 单独占一行右侧 */
.group-head .shelve-chip { margin-left: auto; align-self: center; }
.shelf-bar { display: flex; justify-content: flex-end; margin: 2px 0 8px; }
.shelve-chip {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 10px; font-size: 12px; line-height: 1.6;
  color: var(--text-3); background: var(--surface-2);
  border: 1px solid var(--border); border-radius: 999px;
  cursor: pointer; user-select: none; white-space: nowrap;
  transition: color .15s, background .15s, border-color .15s;
}
.shelve-chip:hover { color: var(--primary); border-color: var(--primary-border); background: var(--primary-soft); }
/* 有东西收着时给个颜色点，一眼知道里面不是空的 */
.shelve-chip.on { color: var(--text-2); border-color: var(--border-strong); }
.shelve-chip .n { font-size: 10.5px; font-weight: 700; color: var(--primary); }
.shelf-row { display: flex; align-items: center; gap: 10px; padding: 10px 4px; }
.shelf-row + .shelf-row { border-top: 1px solid var(--border); }
.shelf-acts { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.shelf-date { width: 148px; padding: 4px 8px; font-size: 12px; }

/* 7 列等宽。必须写成 minmax(0, 1fr)：1fr 的最小值默认是 auto（= 内容最小宽），
   而格子里的待办文字是 nowrap，窄屏下会被内容撑开导致整个日历横向溢出（实测手机端只露出 3 列）。
   minmax(0, ...) 让列宽完全由容器决定，文字改用省略号截断。 */
.cal-grid { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 6px; }
.cal-wd { text-align: center; font-size: 12px; color: var(--text-3); padding: 4px 0; }
.cal-day { border: 1px solid var(--border); border-radius: 10px; min-height: 76px; padding: 5px; background: var(--surface); min-width: 0; }
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

/* 手机专用的圆点概览与选中日清单：桌面端不参与排版（窄屏规则见下方媒体查询） */
.cal-dots, .cal-agenda { display: none; }

.wd-chip { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--border-strong); cursor: pointer; font-size: 12px; }
.wd-chip.on { background: var(--primary); color: #fff; border-color: var(--primary); }

.preview-box { background: var(--surface-2); border-radius: 10px; padding: 12px; margin-top: 4px; }
.big-opt { flex-direction: column; gap: 4px; padding: 18px 12px; height: auto; }

/* ===================== 移动端（≤ 820px） ===================== */
@media (max-width: 820px), (pointer: coarse) and (max-height: 480px) {
  /* 列表/日历切换与「添加待办」：整行铺满，按钮均分，触屏点击更稳 */
  .seg { display: flex; width: 100%; }
  .seg-btn { flex: 1; padding: 10px 0; font-size: 13.5px; }

  /* 筛选行：项目下拉独占一行铺满（长项目名不被截断），第二行是「仅看未完成」+ 摘要 + 清除 */
  .filter-field { flex: 1 1 100%; }
  .filter-select { flex: 1; width: 100%; max-width: none; min-width: 0; min-height: 40px; }
  .chip { padding: 9px 16px; min-height: 36px; }
  .filter-clear { min-height: 36px; }

  /* 待办行：勾选框 + 内容独占首行，操作按钮整体落到第二行 */
  .todo-row { flex-wrap: wrap; align-items: flex-start; row-gap: 8px; padding: 10px 4px; }
  .todo-row > .grow { flex: 1 1 calc(100% - 32px); } /* 100% - 勾选框(22) - 间距(10) */
  .row-acts { margin-left: 0; width: 100%; flex-wrap: wrap; gap: 6px; row-gap: 8px; }
  /* 相邻待办之间加分隔线：换行后单条变高，需要更明确的条目边界 */
  .todo-block + .todo-block { border-top: 1px solid var(--border); }
  .todo-row .icon-btn { width: 36px; height: 36px; min-width: 36px; font-size: 15px; }
  .pomo-btn, .sub-btn { padding: 7px 12px; min-height: 36px; }
  /* 触屏上进度标签要当按钮点：做成 36px 高的胶囊，落点稳 */
  .tag.prog-tag { min-height: 36px; display: inline-flex; align-items: center; padding: 0 12px; font-size: 12px; border-radius: 999px; }
  .pomo-cnt { font-size: 12px; }
  /* 搁置区：标题旁的胶囊抬到 32px 以上（手指落点），弹窗里日期与按钮换到第二行，别把名字挤没 */
  .shelve-chip { min-height: 32px; padding: 0 12px; font-size: 13px; }
  .shelf-row { flex-wrap: wrap; row-gap: 8px; }
  .shelf-acts { width: 100%; }
  .shelf-date { flex: 1; min-width: 0; height: 36px; }
  .shelf-acts .btn.sm { min-height: 36px; }
  /* 「升级」在手机上收进详情弹窗：多这一个图标会让纯待办行多折一行，
     导致列表里各条高度不一致（详见 templates 里 #foot 的「升级」按钮） */
  .act-upgrade { display: none; }

  /* 日历（手机）：7 列 × 390px ≈ 45px/格，塞文字只能露出两个字，认不出来也点不准。
     格子只承载「日期 + 圆点」，完整清单下移到网格下方 .cal-agenda（44px 行高、可点）。
     桌面端不受影响：.cal-items 照常显示，.cal-dots / .cal-agenda 保持 display:none。 */
  .cal-grid { gap: 4px; }
  .cal-day { min-height: 52px; padding: 5px 3px; border-radius: 9px; cursor: pointer; }
  .cal-day.sel-day { border-color: var(--primary); background: var(--primary-soft); box-shadow: 0 0 0 2px var(--primary-soft) inset; }
  .cal-wd { font-size: 11px; padding: 2px 0; }
  .cal-num { font-size: 12px; margin-bottom: 5px; }
  .cal-items { display: none; }
  .cal-dots { display: flex; align-items: center; justify-content: center; gap: 3px; flex-wrap: wrap; min-height: 16px; }
  .cal-dots i { width: 6px; height: 6px; border-radius: 50%; background: var(--primary); }
  .cal-dots i.done { background: var(--border-strong); }
  .cal-dots-more { font-size: 9.5px; color: var(--text-3); line-height: 1; }
  /* 月份切换箭头：图标型按钮的横向热区也要够（原 30px 宽） */
  .cal-nav { min-width: 44px; }

  /* 选中日的完整清单 */
  .cal-agenda { display: block; margin-top: 12px; border-top: 1px solid var(--border); padding-top: 6px; }
  .cal-agenda-head { display: flex; align-items: baseline; justify-content: space-between; padding: 4px 2px 8px; }
  .cal-agenda-date { font-weight: 600; font-size: 13.5px; }
  .cal-agenda-empty { padding: 14px 2px; font-size: 13px; }
  .cal-agenda-row { display: flex; align-items: center; gap: 10px; padding: 10px 6px; min-height: 44px; border-radius: 9px; }
  .cal-agenda-row + .cal-agenda-row { border-top: 1px solid var(--border); }
  .cal-agenda-row.done .grow { color: var(--text-3); text-decoration: line-through; }

  /* 拖拽改期在触屏上不可用（HTML5 拖放不响应触摸事件），隐藏底部放置区；
     手机上改期统一走「⋯ 详情」里的日期字段 */
  .drop-new { display: none; }
  .wd-chip { width: 34px; height: 34px; font-size: 13px; }
  .preview-box { padding: 10px; }
  .big-opt { padding: 14px 10px; }
}
</style>
