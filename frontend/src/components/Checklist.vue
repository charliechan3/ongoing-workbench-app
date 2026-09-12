<script setup>
import { computed, nextTick, ref } from 'vue'
import { useDataStore, checklistItemsOf } from '../stores/data'
import { useInlineRename } from '../composables/useInlineRename'

/**
 * 行动 / 待办的子项子列表（清单式拆分）。
 *
 * 设计约定：
 * - 子项只有 名字 + 完成状态 + 排序，没有番茄钟 / 优先级 / 日期 / 备注，也不参与统计；
 * - 交互只有四种：点击勾选完成、双击改名、在子列表内拖动排序、删除；
 * - parent 传 { actionId, todoId }：因为 Todo 是 Action 的投影，读取时两者取并集，
 *   写入时由 store 决定归属（行动优先），保证投影两侧看到同一份子项；
 * - 拖动只在"本子列表内"排序，不跨条（跨条移动子项没有业务含义）。
 */
const props = defineProps({
  actionId: { type: String, default: '' },
  todoId: { type: String, default: '' }
})

const store = useDataStore()
const parent = computed(() => ({ actionId: props.actionId, todoId: props.todoId }))
const items = computed(() => checklistItemsOf(store.checklist, parent.value))

/* ---- 添加：底部虚线按钮 → 行内输入框，回车连续添加（清空但不收起） ---- */
const adding = ref(false)
const draft = ref('')
const addInput = ref(null)
function openAdd() {
  adding.value = true
  nextTick(() => addInput.value?.focus())
}
async function submitAdd() {
  const text = draft.value.trim()
  if (!text) return
  draft.value = ''
  await store.addChecklistItem(parent.value, text)
  nextTick(() => addInput.value?.focus())
}
function closeAdd() {
  if (draft.value.trim()) return // 有未提交内容时先不收起，避免误触丢输入
  adding.value = false
}

/* ---- 双击改名（与待办/行动的行内改名同一套交互） ---- */
const { renamingId, renameText, startRename, commitRename, cancelRename, renameRef } =
  useInlineRename(async (id, text) => {
    const it = store.checklist.find(x => x.id === id)
    if (!it) return
    if (!text) { store.toast('子项内容不能为空', 'err'); return }
    await store.renameChecklistItem(it, text)
  })

/* ---- 拖动排序：把被拖项移动到目标项的位置，然后整表重编号 ----
   只在"正在拖子项"时接管事件；其它拖拽（例如把待办行拖到某天）直接放行，
   否则会挡住外层的日期放置区。 */
const dragId = ref('')
const overId = ref('')
function onDragStart(it, e) {
  dragId.value = it.id
  overId.value = ''
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', it.id)
  }
}
function onDragOver(it, e) {
  if (!dragId.value || it.id === dragId.value) return
  e.preventDefault()
  e.stopPropagation()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  overId.value = it.id
}
async function onDrop(it, e) {
  if (!dragId.value) return
  e.preventDefault()
  e.stopPropagation()
  const from = items.value.findIndex(x => x.id === dragId.value)
  const to = items.value.findIndex(x => x.id === it.id)
  dragId.value = ''
  overId.value = ''
  if (from < 0 || to < 0 || from === to) return
  const arr = [...items.value]
  const [moved] = arr.splice(from, 1)
  arr.splice(to, 0, moved)
  await store.reorderChecklist(arr)
}
function onDragEnd() { dragId.value = ''; overId.value = '' }
</script>

<template>
  <div class="clist">
    <div v-for="it in items" :key="it.id" class="cl-row"
         :class="{ dragging: dragId === it.id, over: overId === it.id }"
         draggable="true" title="拖动可调整顺序"
         @dragstart="onDragStart(it, $event)" @dragover="onDragOver(it, $event)"
         @drop="onDrop(it, $event)" @dragend="onDragEnd">
      <span class="cl-grip">⋮⋮</span>
      <div class="checkbox cl-box" :class="{ on: it.done }" :title="it.done ? '取消完成' : '标记完成'"
           @click="store.toggleChecklist(it)">✓</div>
      <input v-if="renamingId === it.id" :ref="renameRef" v-model="renameText" class="rename-input"
             @keydown.enter.prevent="commitRename(it.id)" @keydown.esc.prevent="cancelRename"
             @blur="commitRename(it.id)" />
      <span v-else class="cl-name grow" :class="{ done: it.done }" title="双击可改名"
            @dblclick="startRename(it.id, it.name)">{{ it.name }}</span>
      <button class="icon-btn cl-del" title="删除子项" @click="store.removeChecklistItem(it)">✕</button>
    </div>

    <div v-if="adding" class="cl-add">
      <input ref="addInput" v-model="draft" class="input cl-input" placeholder="子项内容，回车添加"
             @keydown.enter.prevent="submitAdd" @keydown.esc.prevent="closeAdd" @blur="closeAdd" />
      <button class="btn sm primary" @click="submitAdd">添加</button>
    </div>
    <button v-else class="cl-addbtn" @click="openAdd">＋ 添加子项</button>
  </div>
</template>

<style scoped>
/* 左虚线导轨表示"从属于上面那条行动/待办" */
.clist { margin: 1px 0 6px 20px; padding-left: 8px; border-left: 2px solid var(--border-strong); display: flex; flex-direction: column; }
.cl-row { display: flex; align-items: center; gap: 7px; padding: 3px 6px; border-radius: 7px; }
.cl-row:hover { background: var(--surface-2); }
.cl-row.dragging { opacity: .4; }
.cl-row.over { box-shadow: inset 0 2px 0 var(--primary); background: var(--primary-soft); }
.cl-grip { color: var(--text-3); font-size: 11px; letter-spacing: -2px; cursor: grab; user-select: none; flex-shrink: 0; }
.cl-box { width: 15px; height: 15px; border-radius: 4px; font-size: 10px; }
.cl-name { font-size: 13px; cursor: text; word-break: break-word; }
.cl-name.done { color: var(--text-3); text-decoration: line-through; }
.cl-del { width: 22px; height: 22px; font-size: 12px; opacity: .5; flex-shrink: 0; }
.cl-row:hover .cl-del { opacity: 1; }
.cl-addbtn {
  align-self: flex-start; margin-top: 2px; border: 1px dashed var(--border-strong); background: transparent;
  color: var(--text-3); font-size: 12px; padding: 3px 10px; border-radius: 7px; cursor: pointer; transition: all .15s;
}
.cl-addbtn:hover { color: var(--primary); border-color: var(--primary); background: var(--primary-soft); }
.cl-add { display: flex; align-items: center; gap: 6px; margin-top: 2px; }
.cl-input { padding: 5px 9px; font-size: 13px; }
</style>
