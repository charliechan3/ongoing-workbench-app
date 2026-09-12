import { nextTick, ref } from 'vue'

/**
 * 列表内联重命名：双击名称 → 原地变成输入框，Enter / 失焦提交，Esc 取消。
 *
 * 用法（模板里三件套必须齐全，缺一就会出现"改完不生效"或"Esc 之后又被保存"）：
 *   const { renamingId, renameText, startRename, commitRename, cancelRename, renameRef } = useInlineRename(save)
 *
 *   <input v-if="renamingId === item.id" :ref="renameRef" v-model="renameText" class="rename-input"
 *          @keydown.enter.prevent="commitRename(item.id)"
 *          @keydown.esc.prevent="cancelRename"
 *          @blur="commitRename(item.id)" />
 *   <span v-else @dblclick.stop="startRename(item.id, item.name)">{{ item.name }}</span>
 *
 * @param {(id: string, text: string) => any} save 提交回调，收到 trim 后的新名称；空/无变化由调用方自行判断
 */
export function useInlineRename(save) {
  const renamingId = ref('') // 正在编辑的条目 id（'' = 无）
  const renameText = ref('')
  let committing = false

  function startRename(id, current) {
    if (renamingId.value === id) return
    renamingId.value = id
    renameText.value = current == null ? '' : String(current)
  }

  function cancelRename() {
    renamingId.value = ''
    renameText.value = ''
  }

  async function commitRename(id) {
    // Esc 已把 renamingId 清空，随后元素卸载可能再触发一次 blur —— 用 id 比对挡掉，
    // 否则"按 Esc 取消"会把编辑中的内容当真保存下来。
    if (renamingId.value !== id || committing) return
    const text = renameText.value.trim()
    renamingId.value = ''
    committing = true
    try {
      await save(id, text)
    } finally {
      committing = false
      renameText.value = ''
    }
  }

  /**
   * 函数式 ref：元素挂载即聚焦 + 全选。
   * 不用 ref 变量是因为 ref 写在 v-for 作用域内时 Vue 会收集成数组（ref_for），拿不到单个元素。
   * dataset 标记保证同一次编辑只聚焦一次，避免后续重渲染反复抢焦点。
   */
  function renameRef(el) {
    if (!el || !el.tagName || el.dataset.renameReady === '1') return
    el.dataset.renameReady = '1'
    nextTick(() => { el.focus(); if (typeof el.select === 'function') el.select() })
  }

  return { renamingId, renameText, startRename, commitRename, cancelRename, renameRef }
}
