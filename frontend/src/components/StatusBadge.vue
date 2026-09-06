<script setup>
import { computed } from 'vue'
const props = defineProps({ status: String })

const map = {
  todo: { label: '待开始', cls: 'gray' },
  doing: { label: '进行中', cls: 'primary' },
  done: { label: '已完成', cls: 'green' },
  paused: { label: '已暂停', cls: 'orange' },
  quit: { label: '已放弃', cls: 'red' },
  wish: { label: '想读/想看', cls: 'blue' },
  back: { label: '已搁置', cls: 'gray' }
}
const info = computed(() => map[props.status] || { label: props.status, cls: 'gray' })
</script>

<template>
  <span class="tag" :class="info.cls"><span class="dot" :style="{ background: 'currentColor', opacity: .7 }" />{{ info.label }}</span>
</template>
