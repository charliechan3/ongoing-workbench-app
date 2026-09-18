<script setup>
defineProps({ title: String, wide: Boolean })
const emit = defineEmits(['close'])
</script>

<template>
  <!-- Teleport 到 body：弹窗绝不能留在页面里。
       各视图的根元素都是 .main-inner（可滚动容器，移动端还叠了 -webkit-overflow-scrolling），
       而 iOS Safari 在祖先可滚动时会把 position:fixed 的包含块从视口换成那个滚动容器 ——
       页面往下滚过一段再打开弹窗，整个遮罩会跟着偏移，抽屉底部的「保存 / 取消」就落到屏幕外，
       手指点不到（WebKit bug 160953；Chromium 不复现，所以只在真机上暴露）。
       挂到 body 下可彻底脱离滚动容器，顺带避免被祖先的 overflow / z-index 裁切。 -->
  <Teleport to="body">
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
  </Teleport>
</template>
