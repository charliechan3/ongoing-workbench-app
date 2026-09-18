import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initViewportVars } from './viewport'
import { initDevHud } from './devhud'
import './style.css'

// 先把「可见视口」写进 CSS 变量（软键盘避让用），再挂载
initViewportVars()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')

// 真机自检面板：只在 URL 带 #diag 时启用（排查移动端布局问题用，正常访问无任何影响）
initDevHud()
