<script setup>
/* 记录进度（「进行中」行动行 / 「待办」行内共用）
   - 传 action：进度记在行动上（未满 100% 自动留续办提醒）
   - 传 todo：绑定行动的待办转给行动（同一份数据）；纯待办写自己的 progress
   填的是**最终累计进度**（绝对值），store 直接置为这个值，不做加法；
   到 100% 只停止新增续办，不自动完成（完成状态由用户手动勾选）。

   这里的那段话就是这条的「完成情况」：只存一份（completionNote），
   进度日志当天的备注也用它，所以列表上不再需要第二个「填完成情况」的入口。 */
import { computed, ref } from 'vue'
import Modal from './Modal.vue'
import ProgressBar from './ProgressBar.vue'
import { useDataStore, today, progressOf, progressAddOn, todoProgressOf } from '../stores/data'

const props = defineProps({
  action: { type: Object, default: null },
  todo: { type: Object, default: null }
})
const emit = defineEmits(['close'])
const store = useDataStore()

// 待办 → 真正承载进度的对象：续办提醒（fromTodoId）看源待办，投影待办/行动续办看行动
const target = computed(() => {
  if (props.action) return props.action
  const t = props.todo
  if (!t) return null
  if (t.fromTodoId) return store.todos.find(x => x.id === t.fromTodoId) || t
  return t
})
// 待办 → 它背后的行动（投影 actionId / 行动续办提醒 fromActionId）
const linkedAct = computed(() => {
  if (props.action) return props.action
  const t = target.value
  const id = t && (t.actionId || t.fromActionId)
  return id ? store.actions.find(a => a.id === id) || null : null
})
const pct = computed(() => linkedAct.value
  ? progressOf(linkedAct.value)
  : todoProgressOf(target.value))
const title = computed(() => `「${(linkedAct.value && linkedAct.value.name) || (target.value && target.value.text) || ''}」`)
const logMap = computed(() => (linkedAct.value ? linkedAct.value.progressLog : target.value && target.value.progressLog) || {})
const todayAdd = computed(() => progressAddOn({ progressLog: logMap.value }, today()))

// 完成情况只有一个出处：绑定行动看行动（投影待办会被同步），纯待办看自己
const noteOwner = computed(() => linkedAct.value || target.value)
const origNote = computed(() => (noteOwner.value && noteOwner.value.completionNote) || '')

const form = ref({ set: pct.value, note: origNote.value })
// 最近几次进度记录（倒序，最多 6 条）
const logRows = computed(() => Object.entries(logMap.value)
  .map(([f, v]) => ({
    f,
    add: Math.round(Number(v && typeof v === 'object' ? v.add : v) || 0),
    note: v && typeof v === 'object' ? String(v.note || '') : ''
  }))
  .filter(r => r.add !== 0)
  .sort((x, y) => y.f.localeCompare(x.f))
  .slice(0, 6))
const signed = (n) => (n > 0 ? `+${n}` : String(n))

/* 完成情况写回：内容只存一份，进度日志里**当天**那条的备注也换成同一段话
   （替换而非拼接，免得同一句话被「；」越叠越长）；当天没记过进度就不动日志。 */
const logPatch = (obj, note) => {
  const d = today()
  const log = (obj && obj.progressLog) || {}
  if (!log[d]) return {}
  const prev = log[d]
  const prevObj = prev && typeof prev === 'object' ? prev : { add: Number(prev) || 0, note: '' }
  // 只换当天那条的备注，base / add 原样保留（base 是当天起点，不能丢）
  return { progressLog: { ...log, [d]: { ...prevObj, note } } }
}

async function writeNote(note) {
  const act = linkedAct.value
  const t = target.value
  if (act) await store.updateAction(act.id, { completionNote: note, ...logPatch(act, note) }, { silent: true })
  else if (t) await store.update('todos', t.id, { completionNote: note, ...logPatch(t, note) }, { silent: true })
}

async function submit() {
  const t = target.value
  if (!t) return
  const val = Math.round(Number(form.value.set))
  if (!Number.isFinite(val) || val < 0 || val > 100) { store.toast('请填写 0-100 的累计进度', 'err'); return }
  const note = (form.value.note || '').trim()
  const noteChanged = note !== (origNote.value || '').trim()
  const pctChanged = val !== pct.value
  if (!pctChanged && !noteChanged) { store.toast('进度和完成情况都没有变化', 'err'); return }
  emit('close')
  if (pctChanged) {
    // 这里只传进度：完成情况单独写一份，避免 store 里同一天的备注用「；」拼成一长串
    if (linkedAct.value) await store.recordProgress(linkedAct.value, { set: val })
    else await store.recordTodoProgress(t, { set: val })
  }
  if (noteChanged) {
    await writeNote(note)
    if (!pctChanged) store.toast(note ? '已更新完成情况' : '已清空完成情况')
  }
}
</script>

<template>
  <Modal title="记录进度" @close="emit('close')">
    <p class="muted mb-12 truncate">{{ title }}</p>
    <div class="prog-now">
      <div class="flex-between mb-8">
        <span class="muted" style="font-size:12px">当前累计进度</span>
        <span class="mono" style="font-weight:700">{{ pct }}%</span>
      </div>
      <ProgressBar :value="pct" />
      <p v-if="todayAdd" class="muted" style="font-size:12px;margin-top:8px">今天已记录 {{ signed(todayAdd) }}%</p>
    </div>
    <label class="field"><span>当前累计进度（%）</span>
      <input v-model.number="form.set" type="number" min="0" max="100" step="5" class="input" autofocus />
    </label>
    <div class="flex gap-4 mb-8" style="flex-wrap:wrap">
      <button v-for="n in [25, 50, 75, 100]" :key="n" class="btn sm" @click="form.set = n">{{ n }}%</button>
    </div>
    <label class="field"><span>完成情况（今天做了什么）</span>
      <textarea v-model="form.note" class="textarea" rows="3" placeholder="一句话记下推进到哪、结果如何…"></textarea>
    </label>
    <p v-if="linkedAct" class="muted" style="font-size:12px">进度记在这条行动上；未满 100% 自动在「待办 → 未分配」生成一条续办提醒，满 100% 停止新增。</p>
    <p v-else-if="todo && todo.fromTodoId" class="muted" style="font-size:12px">进度与源待办同步；未满 100% 保留一条续办提醒，满 100% 停止新增。</p>
    <p v-else class="muted" style="font-size:12px">进度记在这条待办上；未满 100% 自动在「待办 → 未分配」生成一条续办提醒，满 100% 停止新增。</p>
    <div v-if="logRows.length" class="prog-hist">
      <div class="muted mb-8" style="font-size:12px">最近记录</div>
      <div v-for="r in logRows" :key="r.f" class="prog-log-row">
        <span class="mono">{{ r.f }}</span>
        <span class="prog-add">{{ signed(r.add) }}%</span>
        <span class="muted grow truncate" :title="r.note">{{ r.note || '—' }}</span>
      </div>
    </div>
    <template #foot>
      <button class="btn" @click="emit('close')">取消</button>
      <button class="btn primary" @click="submit">记录</button>
    </template>
  </Modal>
</template>

<style scoped>
.prog-now { background: var(--surface-2); border-radius: 10px; padding: 12px; margin-bottom: 14px; }
.prog-hist { background: var(--surface-2); border-radius: 10px; padding: 12px; margin-top: 10px; }
.prog-log-row { display: flex; align-items: center; gap: 10px; font-size: 12px; padding: 3px 0; }
.prog-add { color: var(--primary); font-weight: 600; min-width: 42px; }
</style>
