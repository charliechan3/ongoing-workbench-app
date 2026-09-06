<script setup>
import { computed } from 'vue'
const props = defineProps({ value: { type: Number, default: 0 }, tone: String })
const pct = computed(() => Math.max(0, Math.min(100, props.value)))
</script>

<template>
  <div class="progress" :class="tone && tone !== 'primary' ? tone : ''">
    <div :style="{ width: pct + '%' }"></div>
  </div>
</template>
