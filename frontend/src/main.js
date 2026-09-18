import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initViewportVars } from './viewport'
import './style.css'

// 先把「可见视口」写进 CSS 变量（软键盘避让用），再挂载
initViewportVars()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
