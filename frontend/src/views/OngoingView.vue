<script setup>
import { computed, ref } from 'vue'
import { useDataStore, projectProgress, today, isRepeat } from '../stores/data'
import Modal from '../components/Modal.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ProgressBar from '../components/ProgressBar.vue'

const store = useDataStore()
const tab = ref('projects') // projects | notes
const showModal = ref(false)
const modalType = ref('project')
const form = ref({})
const editing = ref(null)
const selectedArea = ref('all')
const selectedProject = ref(null)
const showTaskModal = ref(false)
const taskForm = ref({})
const showActionModal = ref(false)
const actionForm = ref({})
const showNoteModal = ref(false)
const noteForm = ref({})
const notePreview = ref(false)
const imgInput = ref(null)

/* ===== 区域 ===== */
const areas = computed(() => store.areas)
const areasWithStats = computed(() => store.areas.map(a => {
  const ps = store.projects.filter(p => p.areaId === a.id)
  return { ...a, projectCount: ps.length, doneCount: ps.filter(p => p.status === 'done').length }
}))

/* ===== 项目 ===== */
const visibleProjects = computed(() => {
  let list = store.projectCards
  if (selectedArea.value !== 'all') list = list.filter(p => p.areaId === selectedArea.value)
  return list
})

function openCreate(type, data = {}) {
  modalType.value = type
  editing.value = null
  form.value = { priority: 'P3', status: 'todo', ...data }
  showModal.value = true
}
function openEdit(type, item) {
  modalType.value = type
  editing.value = item
  form.value = { ...item }
  showModal.value = true
}
async function save() {
  if (editing.value) await store.update(modalType.value === 'area' ? 'areas' : 'projects', editing.value.id, form.value)
  else await store.create(modalType.value === 'area' ? 'areas' : 'projects', form.value)
  showModal.value = false
}
async function del(type, item) {
  if (!confirm(`确定删除「${item.name}」？其子项也将失去关联。`)) return
  await store.remove(type === 'area' ? 'areas' : 'projects', item.id)
}

/* ===== 项目详情：任务 + 行动 ===== */
const projTasks = computed(() => selectedProject.value ? store.tasks.filter(t => t.projectId === selectedProject.value.id) : [])
const projActions = computed(() => selectedProject.value ? store.actions.filter(a => a.projectId === selectedProject.value.id && !a.taskId) : [])
const projNotes = computed(() => selectedProject.value
  ? store.notes
      .filter(n => n.projectId === selectedProject.value.id)
      .sort((a, b) => (b.updatedAt || b.createdAt || '').localeCompare(a.updatedAt || a.createdAt || ''))
  : [])
const taskActions = (taskId) => store.actions.filter(a => a.taskId === taskId)
const projProgress = computed(() => selectedProject.value ? projectProgress(selectedProject.value, projTasks.value, store.actions) : 0)
// 行动"当前完成态"：重复行动以今天是否完成(doneDates)表达，避免昨日完成后一直显示勾选
const actDoneNow = (a) => isRepeat(a) ? ((a.doneDates || []).includes(today())) : (a.status === 'done')
// 番茄数与待办同步：已完成数在 action 侧（finishPomodoro 双写），估算总数只在 todo 侧（后端 Action 无该字段），实时读绑定 todo
const actTodo = (a) => store.todos.find(t => t.actionId === a.id)
const actPomoTotal = (a) => (a.pomoEstimate || 0) || (actTodo(a)?.pomoEstimate || 0)
// 完成率：已完成 = 100%；否则按番茄进度 pomoCount/估算总数（无估算记 0），上限 100%
const actRate = (a) => {
  if (a.status === 'done') return 100
  const est = actPomoTotal(a)
  return est > 0 ? Math.min(Math.round(((a.pomoCount || 0) / est) * 100), 100) : 0
}
const priOrd = (p) => p === 'P1' ? 0 : p === 'P2' ? 1 : 2
// 项目详情行动排序：完成率升序（未动工的排前面），再按优先级 P1 > P2 > P3
const sortActions = (list) => [...list].sort((x, y) => actRate(x) - actRate(y) || priOrd(x.priority) - priOrd(y.priority))
const priCls = (p) => p === 'P1' ? 'red' : p === 'P2' ? 'orange' : 'gray'
const projActionsSorted = computed(() => sortActions(projActions.value))

function openTask(t = null) {
  taskForm.value = t ? { ...t } : { projectId: selectedProject.value?.id, areaId: selectedProject.value?.areaId, priority: 'P3', status: 'todo' }
  showTaskModal.value = true
}
async function saveTask() {
  if (taskForm.value.id) await store.update('tasks', taskForm.value.id, taskForm.value)
  else await store.create('tasks', taskForm.value)
  showTaskModal.value = false
}
function openAction(t = null, taskId = null) {
  // repeat 必须归一为对象：保存时无重复会被置为 null（后端可空 Map），直接 {...t} 会让弹窗 v-model="actionForm.repeat.type" 抛错，表现为"编辑一次后再也点不开"
  // pomoEstimate 兼容存量数据：旧行动侧为 null 而估算存在待办侧，预填合并值，避免原样保存时 null 归零抹掉待办侧估算
  const estOf = (a) => (a.pomoEstimate != null ? a.pomoEstimate : (actPomoTotal(a) || 0))
  actionForm.value = t
    ? { ...t, repeat: t.repeat ? { ...t.repeat, weekdays: [...(t.repeat.weekdays || [])] } : { type: '' }, pomoEstimate: estOf(t) }
    : { taskId, projectId: selectedProject.value?.id, areaId: selectedProject.value?.areaId, priority: 'P3', status: 'todo', startDate: today(), repeat: { type: '' }, pomoEstimate: 0 }
  showActionModal.value = true
}
async function saveAction() {
  const f = { ...actionForm.value }
  if (f.repeat && !f.repeat.type) f.repeat = null
  delete f.repeatDaysText
  if (f.id) await store.updateAction(f.id, f)
  else await store.addAction(f)
  showActionModal.value = false
}
async function delTask(t) {
  if (!confirm(`删除任务「${t.name}」？`)) return
  await store.remove('tasks', t.id)
}
async function delAction(a) {
  if (!confirm(`删除行动「${a.name}」？待办中的同步条目将一并删除。`)) return
  await store.removeAction(a.id)
}

/* ---- 完成情况弹窗（行动侧，与绑定待办双向同步） ---- */
const showCompletion = ref(false)
const completionTarget = ref(null)
const completionText = ref('')
const completionMarkDone = ref(false)
function openCompletion(a) {
  completionTarget.value = a
  completionText.value = a.completionNote || ''
  completionMarkDone.value = false
  showCompletion.value = true
}
async function submitCompletion() {
  const a = completionTarget.value
  if (!a) return
  const note = (completionText.value || '').trim()
  showCompletion.value = false
  // updateAction 会把 completionNote 双向同步到绑定待办
  await store.updateAction(a.id, { completionNote: note }, { msg: note ? '已记录完成情况' : '已清空完成情况' })
  if (completionMarkDone.value && !actDoneNow(a)) await store.completeAction(a)
}

/* ---- 番茄数校准（仅改属性，不写番茄记录，不影响统计/今日番茄/今日工时） ---- */
const showPomoCalib = ref(false)
const pomoCalibTarget = ref(null)
const pomoCalibForm = ref({ pomoCount: 0, pomoEstimate: 0 })
function openPomoCalib(a) {
  pomoCalibTarget.value = a
  pomoCalibForm.value = { pomoCount: a.pomoCount || 0, pomoEstimate: actPomoTotal(a) || 0 }
  showPomoCalib.value = true
}
async function submitPomoCalib() {
  const a = pomoCalibTarget.value
  if (!a) return
  await store.calibrateActionPomo(a, pomoCalibForm.value)
  showPomoCalib.value = false
}

/* ===== 工作笔记 ===== */
const notes = computed(() => [...store.notes].sort((a, b) => (b.updatedAt || b.createdAt || '').localeCompare(a.updatedAt || a.createdAt || '')))
function openNote(n = null) {
  noteForm.value = n ? { ...n } : { title: '', content: '', projectId: selectedProject.value?.id || '' }
  notePreview.value = false
  showNoteModal.value = true
}
async function saveNote() {
  const f = { ...noteForm.value }
  const nowIso = new Date().toISOString()
  if (f.id) await store.update('notes', f.id, { ...f, updatedAt: nowIso }, { silent: true })
  else await store.create('notes', { ...f, publishedAt: nowIso, updatedAt: nowIso, createdAt: nowIso }, { silent: true })
  showNoteModal.value = false
  store.toast('笔记已保存')
}
function insertImage() {
  imgInput.value?.click()
}
function onImgFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    noteForm.value.content += `\n\n![${file.name}](${reader.result})\n`
  }
  reader.readAsDataURL(file)
}
const md = (s) => {
  if (!s) return ''
  let h = s
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  h = h.replace(/```([\s\S]*?)```/g, (_, c) => `<pre><code>${c.replace(/\n$/, '')}</code></pre>`)
  h = h.replace(/^### (.*)$/gm, '<h3>$1</h3>').replace(/^## (.*)$/gm, '<h2>$1</h2>').replace(/^# (.*)$/gm, '<h1>$1</h1>')
  h = h.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/\*(.*?)\*/g, '<em>$1</em>').replace(/`(.*?)`/g, '<code>$1</code>')
  h = h.replace(/!\[(.*?)\]\((.*?)\)/g, '<img src="$2" alt="$1"/>')
  h = h.replace(/\[(.*?)\]\((.*?)\)/g, '<a href="$2">$1</a>')
  h = h.replace(/^> (.*)$/gm, '<blockquote>$1</blockquote>')
  h = h.replace(/^- (.*)$/gm, '<li>$1</li>')
  h = h.replace(/^\d+\. (.*)$/gm, '<li>$1</li>')
  h = h.replace(/(<li>[\s\S]*?<\/li>)(?!(?:\s*<li>))/g, '<ul>$1</ul>')
  h = h.replace(/^---$/gm, '<hr/>')
  h = h.replace(/\n{2,}/g, '</p><p>').replace(/\n/g, '<br/>')
  return `<p>${h}</p>`
}

const areaOpts = computed(() => [{ id: 'all', name: '全部区域' }, ...store.areas])
</script>

<template>
  <div class="main-inner">
    <div class="page-head">
      <div>
        <div class="page-title">进行中</div>
        <div class="page-sub">区域 → 项目 → 任务 → 行动，把职业成长拆解到可执行。</div>
      </div>
      <div class="flex gap-8">
        <div class="seg">
          <button class="seg-btn" :class="{ on: tab === 'projects' }" @click="tab = 'projects'">项目</button>
          <button class="seg-btn" :class="{ on: tab === 'notes' }" @click="tab = 'notes'">笔记</button>
        </div>
        <button v-if="tab === 'projects'" class="btn primary" @click="openCreate('area')">＋ 新建区域</button>
        <button v-else-if="tab === 'notes'" class="btn primary" @click="openNote()">＋ 新建笔记</button>
      </div>
    </div>

    <!-- ========== 项目 Tab ========== -->
    <template v-if="tab === 'projects'">
      <!-- 区域卡片 -->
      <div class="grid-4 mb-16">
        <div v-for="a in areasWithStats" :key="a.id" class="card area-card" @click="selectedArea = selectedArea === a.id ? 'all' : a.id" :class="{ on: selectedArea === a.id }">
          <div class="flex-between">
            <span class="area-name" :style="{ color: a.color || 'var(--primary)' }">{{ a.name }}</span>
            <span class="muted">{{ a.doneCount }}/{{ a.projectCount }}</span>
          </div>
          <div class="muted truncate mt-8">{{ a.desc }}</div>
        </div>
      </div>

      <!-- 项目列表 -->
      <div v-if="!selectedProject" class="grid-3">
        <div v-for="p in visibleProjects" :key="p.id" class="card proj-card" @click="selectedProject = p">
          <div class="flex-between mb-8">
            <span class="proj-color" :style="{ background: p.color || '#9b6dff' }"></span>
            <StatusBadge :status="p.status" />
          </div>
          <div style="font-weight:600;font-size:15px;margin-bottom:4px">{{ p.name }}</div>
          <div class="muted truncate mb-12" style="min-height:20px">{{ p.desc || '暂无描述' }}</div>
          <ProgressBar :value="p.progress" />
          <div class="flex-between mt-8">
            <span class="muted">{{ p.doneCount }}/{{ p.totalCount }} 项</span>
            <span class="muted mono">{{ p.progress }}%</span>
          </div>
          <div class="flex mt-12" style="justify-content:flex-end">
            <button class="icon-btn" @click.stop="openEdit('project', p)">✎</button>
            <button class="icon-btn" @click.stop="del('project', p)">🗑</button>
          </div>
        </div>
        <div class="card new-card" @click="openCreate('project', { areaId: selectedArea !== 'all' ? selectedArea : store.areas[0]?.id, status: 'doing' })">
          <div class="new-plus">＋</div>
          <div class="muted">新建项目</div>
        </div>
      </div>

      <!-- 项目详情 -->
      <template v-else>
        <button class="btn ghost sm mb-16" @click="selectedProject = null">‹ 返回项目列表</button>
        <div class="card mb-16">
          <div class="flex-between">
            <div class="flex gap-12">
              <span class="proj-color big" :style="{ background: selectedProject.color || '#9b6dff' }"></span>
              <div>
                <div style="font-size:18px;font-weight:700">{{ selectedProject.name }}</div>
                <div class="muted mt-8">{{ selectedProject.desc }}</div>
              </div>
            </div>
            <div class="flex gap-8">
              <StatusBadge :status="selectedProject.status" />
              <button class="btn sm" @click="openEdit('project', selectedProject)">编辑</button>
              <button class="btn sm primary" @click="openTask()">＋ 任务</button>
              <button class="btn sm" @click="openAction(null, null)">＋ 行动</button>
            </div>
          </div>
          <div class="flex gap-12 mt-16" style="align-items:center">
            <span class="muted" style="width:52px">进度</span>
            <div class="grow"><ProgressBar :value="projProgress" /></div>
            <span class="mono muted">{{ projProgress }}%</span>
          </div>
        </div>

        <!-- 任务列表 -->
        <div v-for="t in projTasks" :key="t.id" class="card mb-16 task-block">
          <div class="flex-between mb-8">
            <div class="flex gap-8 grow">
              <div class="grow">
                <span style="font-weight:600">{{ t.name }}</span>
              </div>
            </div>
            <div class="flex gap-4">
              <button class="btn sm" @click="openAction(null, t.id)">＋ 行动</button>
              <button class="icon-btn" @click="openTask(t)">✎</button>
              <button class="icon-btn" @click="delTask(t)">🗑</button>
            </div>
          </div>
          <div v-for="a in sortActions(taskActions(t.id))" :key="a.id" class="act-line">
            <div class="checkbox" :class="{ on: actDoneNow(a) }" @click="store.completeAction(a)">✓</div>
            <span class="grow" :style="{ textDecoration: actDoneNow(a) ? 'line-through' : 'none', color: actDoneNow(a) ? 'var(--text-3)' : 'inherit' }">{{ a.name }}</span>
            <span class="tag" :class="priCls(a.priority)">{{ a.priority || 'P3' }}</span>
            <button class="icon-btn completion-btn" :class="{ on: a.completionNote }"
                    :title="a.completionNote ? '完成情况：' + a.completionNote + '（点击编辑）' : '填写完成情况'"
                    @click="openCompletion(a)">📝</button>
            <span v-if="a.repeat?.type" class="tag cyan">↻ {{ a.repeat.type === 'daily' ? '每日' : a.repeat.type === 'weekly' ? '每周' : '每月' }}</span>
            <span class="muted pomo-calib" title="已完成番茄/番茄估算（与待办同步），点击可校准" @click="openPomoCalib(a)">🍅 {{ a.pomoCount || 0 }}/{{ actPomoTotal(a) }}</span>
            <button class="icon-btn" @click="openAction(a, a.taskId)">✎</button>
            <button class="icon-btn" @click="delAction(a)">🗑</button>
          </div>
          <div v-if="!taskActions(t.id).length" class="muted" style="padding:4px 0 0">暂无行动，点击「＋ 行动」添加</div>
        </div>

        <!-- 独立行动 -->
        <div v-if="projActions.length" class="card mb-16">
          <div class="mb-8" style="font-weight:600">独立行动</div>
          <div v-for="a in projActionsSorted" :key="a.id" class="act-line">
            <div class="checkbox" :class="{ on: actDoneNow(a) }" @click="store.completeAction(a)">✓</div>
            <span class="grow" :style="{ textDecoration: actDoneNow(a) ? 'line-through' : 'none', color: actDoneNow(a) ? 'var(--text-3)' : 'inherit' }">{{ a.name }}</span>
            <span class="tag" :class="priCls(a.priority)">{{ a.priority || 'P3' }}</span>
            <button class="icon-btn completion-btn" :class="{ on: a.completionNote }"
                    :title="a.completionNote ? '完成情况：' + a.completionNote + '（点击编辑）' : '填写完成情况'"
                    @click="openCompletion(a)">📝</button>
            <span v-if="a.repeat?.type" class="tag cyan">↻ {{ a.repeat.type === 'daily' ? '每日' : a.repeat.type === 'weekly' ? '每周' : '每月' }}</span>
            <span class="muted pomo-calib" title="已完成番茄/番茄估算（与待办同步），点击可校准" @click="openPomoCalib(a)">🍅 {{ a.pomoCount || 0 }}/{{ actPomoTotal(a) }}</span>
            <button class="icon-btn" @click="openAction(a, null)">✎</button>
            <button class="icon-btn" @click="delAction(a)">🗑</button>
          </div>
        </div>

        <!-- 项目笔记 -->
        <div class="flex-between mb-12">
          <div class="flex gap-8" style="align-items:baseline">
            <span style="font-weight:600">📝 项目笔记</span>
            <span class="muted" v-if="projNotes.length">共 {{ projNotes.length }} 篇</span>
          </div>
          <button class="btn sm primary" @click="openNote()">＋ 新建笔记</button>
        </div>
        <div v-if="projNotes.length" class="grid-3">
          <div v-for="n in projNotes" :key="n.id" class="card note-card" @click="openNote(n)">
            <div class="flex-between mb-8">
              <span class="tag primary">📝 笔记</span>
              <span class="muted">{{ (n.updatedAt || n.createdAt || '').slice(0, 10) }}</span>
            </div>
            <div style="font-weight:600;font-size:15px;margin-bottom:6px" class="truncate">{{ n.title || '（无标题）' }}</div>
            <div class="muted md-trunc" v-html="md(n.content)"></div>
          </div>
        </div>
        <div v-else class="card mb-16" style="color:var(--text-3);border-style:dashed;justify-content:center;align-items:center;padding:26px;text-align:center">
          暂无绑定笔记 — 点击「＋ 新建笔记」把项目进展、复盘与沉淀记下来。
        </div>
      </template>
    </template>

    <!-- ========== 笔记 Tab ========== -->
    <template v-else-if="tab === 'notes'">
      <div class="grid-3">
        <div v-for="n in notes" :key="n.id" class="card note-card" @click="openNote(n)">
          <div class="flex-between mb-8">
            <span class="tag primary">📝 {{ store.projectMap[n.projectId]?.name || '未分类' }}</span>
            <span class="muted">{{ (n.updatedAt || n.createdAt || '').slice(0, 10) }}</span>
          </div>
          <div style="font-weight:600;font-size:15px;margin-bottom:6px">{{ n.title }}</div>
          <div class="muted md-trunc" v-html="md(n.content)"></div>
        </div>
        <div class="card new-card" @click="openNote()">
          <div class="new-plus">＋</div>
          <div class="muted">新建笔记</div>
        </div>
      </div>
    </template>

    <!-- 区域/项目 弹窗 -->
    <Modal v-if="showModal" :title="`${editing ? '编辑' : '新建'}${modalType === 'area' ? '区域' : '项目'}`" @close="showModal = false">
      <label class="field"><span>名称</span><input v-model="form.name" class="input" /></label>
      <label class="field"><span>描述</span><textarea v-model="form.desc" class="textarea" rows="3"></textarea></label>
      <div class="grid-2">
        <label class="field"><span>状态</span>
          <select v-model="form.status" class="select">
            <option value="todo">待开始</option><option value="doing">进行中</option><option value="done">已完成</option><option value="paused">已暂停</option><option value="quit">已放弃</option>
          </select>
        </label>
        <label class="field"><span>优先级</span>
          <select v-model="form.priority" class="select">
            <option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option>
          </select>
        </label>
      </div>
      <template v-if="modalType === 'project'">
        <div class="grid-2">
          <label class="field"><span>所属区域</span>
            <select v-model="form.areaId" class="select">
              <option v-for="a in store.areas" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </label>
          <label class="field"><span>颜色</span><input v-model="form.color" type="color" class="input" style="padding:4px;height:36px" /></label>
        </div>
        <label class="field"><span>起止日期</span>
          <div class="flex gap-8">
            <input v-model="form.startDate" type="date" class="input" /><input v-model="form.endDate" type="date" class="input" />
          </div>
        </label>
      </template>
      <template #foot>
        <button class="btn" @click="showModal = false">取消</button>
        <button class="btn primary" @click="save">保存</button>
      </template>
    </Modal>

    <!-- 任务弹窗 -->
    <Modal v-if="showTaskModal" title="任务" @close="showTaskModal = false">
      <label class="field"><span>名称</span><input v-model="taskForm.name" class="input" /></label>
      <label class="field"><span>描述</span><textarea v-model="taskForm.desc" class="textarea" rows="3"></textarea></label>
      <div class="grid-2">
        <label class="field"><span>状态</span>
          <select v-model="taskForm.status" class="select">
            <option value="todo">待开始</option><option value="doing">进行中</option><option value="done">已完成</option>
          </select>
        </label>
        <label class="field"><span>优先级</span>
          <select v-model="taskForm.priority" class="select"><option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option></select>
        </label>
      </div>
      <template #foot>
        <button class="btn" @click="showTaskModal = false">取消</button>
        <button class="btn primary" @click="saveTask">保存</button>
      </template>
    </Modal>

    <!-- 行动弹窗 -->
    <Modal v-if="showActionModal" title="行动" @close="showActionModal = false">
      <label class="field"><span>名称</span><input v-model="actionForm.name" class="input" /></label>
      <label class="field"><span>描述</span><textarea v-model="actionForm.desc" class="textarea" rows="2"></textarea></label>
      <label class="field"><span>完成情况（将同步到待办）</span><textarea v-model="actionForm.completionNote" class="textarea" rows="3" placeholder="做完后的结果记录…"></textarea></label>
      <div class="grid-2">
        <label class="field"><span>番茄估算总数（将同步到待办）</span>
          <input v-model.number="actionForm.pomoEstimate" type="number" min="0" class="input" />
        </label>
        <label class="field"><span>优先级</span>
          <select v-model="actionForm.priority" class="select">
            <option value="P1">P1</option><option value="P2">P2</option><option value="P3">P3</option>
          </select>
        </label>
      </div>
      <label class="field"><span>状态</span>
        <select v-model="actionForm.status" class="select">
          <option value="todo">待开始</option><option value="doing">进行中</option><option value="done">已完成</option>
        </select>
      </label>
      <div class="grid-2">
        <label class="field"><span>开始日期（将同步到待办）</span><input v-model="actionForm.startDate" type="date" class="input" /></label>
        <label class="field"><span>重复</span>
          <select v-model="actionForm.repeat.type" class="select">
            <option value="">不重复</option><option value="daily">每日</option><option value="weekly">每周</option><option value="monthly">每月</option>
          </select>
        </label>
      </div>
      <div v-if="actionForm.repeat?.type === 'weekly'" class="field">
        <span>星期</span>
        <div class="flex">
          <label v-for="(w, i) in ['日','一','二','三','四','五','六']" :key="w" class="wd-chip" :class="{ on: (actionForm.repeat.weekdays || []).includes(i) }"
                 @click="actionForm.repeat.weekdays = actionForm.repeat.weekdays || []; actionForm.repeat.weekdays.includes(i) ? actionForm.repeat.weekdays = actionForm.repeat.weekdays.filter(x => x !== i) : actionForm.repeat.weekdays.push(i)">{{ w }}</label>
        </div>
      </div>
      <div v-if="actionForm.repeat?.type === 'monthly'" class="field">
        <span>每月日期（逗号分隔）</span>
        <input v-model="actionForm.repeatDaysText" class="input" placeholder="如 1,15" @change="actionForm.repeat.days = actionForm.repeatDaysText.split(/[,，\s]+/).map(Number).filter(n => n >= 1 && n <= 31)" />
      </div>
      <template #foot>
        <button class="btn" @click="showActionModal = false">取消</button>
        <button class="btn primary" @click="saveAction">保存</button>
      </template>
    </Modal>

    <!-- 完成情况弹窗（行动侧） -->
    <Modal v-if="showCompletion" title="完成情况" @close="showCompletion = false">
      <p class="muted mb-12 truncate">「{{ completionTarget?.name }}」</p>
      <label class="field"><span>完成情况</span>
        <textarea v-model="completionText" class="textarea" rows="5" autofocus
                  placeholder="这次做得怎么样？结果如何？有什么值得记录的…"></textarea>
      </label>
      <label class="flex gap-8" style="font-size:13px;cursor:pointer">
        <input type="checkbox" v-model="completionMarkDone" />
        <span>同时标记为已完成</span>
      </label>
      <p class="muted" style="font-size:12px">完成情况将同步到绑定的待办。</p>
      <template #foot>
        <button class="btn" @click="showCompletion = false">取消</button>
        <button class="btn primary" @click="submitCompletion">保存</button>
      </template>
    </Modal>

    <!-- 番茄数校准弹窗（仅改属性，不写番茄记录） -->
    <Modal v-if="showPomoCalib" title="校准番茄数" @close="showPomoCalib = false">
      <p class="muted mb-12 truncate">「{{ pomoCalibTarget?.name }}」</p>
      <div class="grid-2">
        <label class="field"><span>已完成番茄</span>
          <input v-model.number="pomoCalibForm.pomoCount" type="number" min="0" class="input" />
        </label>
        <label class="field"><span>番茄估算总数</span>
          <input v-model.number="pomoCalibForm.pomoEstimate" type="number" min="0" class="input" />
        </label>
      </div>
      <p class="muted" style="font-size:12px">仅校准显示数值：不会写入番茄钟记录，不影响统计页与首页的今日番茄/今日工时；完成数将同步到绑定的待办。</p>
      <template #foot>
        <button class="btn" @click="showPomoCalib = false">取消</button>
        <button class="btn primary" @click="submitPomoCalib">保存</button>
      </template>
    </Modal>

    <!-- 笔记弹窗 -->
    <Modal v-if="showNoteModal" :title="noteForm.id ? '编辑笔记' : '新建笔记'" wide @close="showNoteModal = false">
      <div class="flex gap-8 mb-12">
        <input v-model="noteForm.title" class="input grow" placeholder="笔记标题" />
        <select v-model="noteForm.projectId" class="select" style="width:170px">
          <option value="">未分类</option>
          <option v-for="p in store.projects" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>
      <div class="flex gap-8 mb-8">
        <button class="btn sm" @click="notePreview = !notePreview">{{ notePreview ? '✎ 编辑' : '👁 预览' }}</button>
        <button class="btn sm" @click="insertImage">🖼 插入图片</button>
        <input ref="imgInput" type="file" accept="image/*" hidden @change="onImgFile" />
        <span class="muted">支持 Markdown 语法</span>
      </div>
      <textarea v-if="!notePreview" v-model="noteForm.content" class="textarea" rows="14" style="font-family:var(--mono);font-size:13px"></textarea>
      <div v-else class="md preview-md" v-html="md(noteForm.content)"></div>
      <template #foot>
        <button class="btn" @click="showNoteModal = false">取消</button>
        <button class="btn primary" @click="saveNote">保存笔记</button>
      </template>
    </Modal>

  </div>
</template>

<style scoped>
.seg { display: flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.seg-btn { border: none; background: transparent; padding: 5px 14px; border-radius: 8px; font-size: 13px; color: var(--text-2); cursor: pointer; }
.seg-btn.on { background: var(--surface); color: var(--primary); font-weight: 600; box-shadow: var(--shadow-sm); }

.area-card { cursor: pointer; border-color: var(--border); transition: all .15s; }
.area-card:hover { border-color: var(--primary-border); }
.area-card.on { border-color: var(--primary); background: var(--primary-soft); }
.area-name { font-weight: 700; font-size: 14px; }

.proj-card { cursor: pointer; transition: all .18s ease; display: flex; flex-direction: column; }
.proj-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); border-color: var(--primary-border); }
.proj-color { width: 26px; height: 6px; border-radius: 999px; display: inline-block; }
.proj-color.big { width: 8px; height: 40px; border-radius: 4px; }

.new-card { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 160px; border-style: dashed; cursor: pointer; color: var(--text-3); transition: all .15s; }
.new-card:hover { border-color: var(--primary); color: var(--primary); background: var(--primary-soft); }
.new-plus { font-size: 26px; font-weight: 300; }

.task-block { border-left: 3px solid var(--primary-border); }
.act-line { display: flex; align-items: center; gap: 10px; padding: 6px 4px 6px 26px; border-radius: 8px; }
.act-line:hover { background: var(--surface-2); }
.completion-btn.on { background: var(--primary-soft); border-radius: 6px; }
.pomo-calib { cursor: pointer; border-bottom: 1px dashed transparent; }
.pomo-calib:hover { color: var(--primary); border-bottom-color: var(--primary); }

.note-card { cursor: pointer; transition: all .18s; }
.note-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.md-trunc { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; max-height: 64px; }
.preview-md { border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; min-height: 200px; background: var(--surface); }

.wd-chip { width: 28px; height: 28px; border-radius: 8px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--border-strong); cursor: pointer; font-size: 12px; }
.wd-chip.on { background: var(--primary); color: #fff; border-color: var(--primary); }
</style>
