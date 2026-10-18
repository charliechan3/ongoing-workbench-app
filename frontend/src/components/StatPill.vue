<script setup>
/**
 * 行尾的「进度 + 完成情况」状态胶囊。
 *
 * 设计约定：
 * - 进度（推到哪一步了）和完成情况（结果怎么样、今天干了什么）是同一件事的两面，
 *   内容也只存一份，所以这里只做**一个**按钮：整颗胶囊是一个入口，点开就是「记录进度」，
 *   那里的文本框就是这条的完成情况；
 * - 重复条目没有累计进度，就只显示「完成情况」文字；
 * - 只负责展示与触发，具体记到行动还是待办上由调用方决定（事件往上抛）。
 */
const props = defineProps({
  // 是否显示进度段：重复条目没有累计进度，传 false
  showProgress: { type: Boolean, default: true },
  // 当前累计进度 0-100
  pct: { type: Number, default: 0 },
  // 完成情况
  note: { type: String, default: '' }
})

defineEmits(['open'])

const tip = () => {
  if (!props.showProgress) return props.note ? `完成情况：${props.note}（点击编辑）` : '填写完成情况'
  const head = props.pct > 0 ? `当前进度 ${props.pct}%` : '还没记录进度'
  const tail = props.note ? `｜完成情况：${props.note}` : ''
  return `${head}${tail}（点击记录进度与完成情况）`
}
</script>

<template>
  <button class="stat-pill" :class="{ solo: !props.showProgress }" :title="tip()" @click.stop="$emit('open')">
    <span v-if="props.showProgress" class="sp-prog" :class="{ on: props.pct > 0 }">📈 {{ props.pct > 0 ? props.pct + '%' : '进度' }}</span>
    <!-- 只有这一颗的时候（重复条目）桌面端补一句说明，手机上省掉留给落点 -->
    <span v-if="!props.showProgress" class="sp-label">完成情况</span>
  </button>
</template>

<style scoped>
/* 一颗胶囊一个入口：外框统一，里面是「进度（+ 完成情况说明）」 */
.stat-pill {
  display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0;
  height: 26px; padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  background: var(--surface);
  font-family: inherit; font-size: 12px; line-height: 1; color: var(--text-2);
  white-space: nowrap; cursor: pointer;
  transition: background .15s, border-color .15s, color .15s;
}
.stat-pill:hover { background: var(--primary-soft); border-color: var(--primary-border); color: var(--primary); }
.stat-pill .sp-prog { display: inline-flex; align-items: center; gap: 3px; }
/* 记过进度：进度这半截点亮 */
.stat-pill .sp-prog.on { color: var(--primary); font-weight: 600; }
/* 只有「完成情况」一段（重复条目）时不需要额外间距 */
.stat-pill.solo { padding: 0 10px 0 8px; }
.stat-pill .sp-label { font-size: 11.5px; color: var(--text-3); }

/* 手机：整颗抬到 36px，落点稳 */
@media (max-width: 820px), (pointer: coarse) and (max-height: 480px) {
  .stat-pill { height: 36px; padding: 0 13px; font-size: 12.5px; }
  .stat-pill.solo { padding: 0 12px; }
}
</style>
