<script setup>
defineProps({ title: String, wide: Boolean })
const emit = defineEmits(['close'])
</script>

<template>
  <div class="modal-mask" @click.self="emit('close')">
    <div class="modal" :class="{ wide }">
      <div class="modal-head">
        <h3>{{ title }}</h3>
        <button class="icon-btn" @click="emit('close')">✕</button>
      </div>
      <div class="modal-body"><slot /></div>
      <div class="modal-foot"><slot name="foot" /></div>
    </div>
  </div>
</template>
