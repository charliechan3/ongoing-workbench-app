import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/AuthView.vue'), meta: { public: true, title: '登录' } },
  { path: '/', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '首页', icon: '⌂' } },
  { path: '/todo', name: 'todo', component: () => import('../views/TodoView.vue'), meta: { title: 'todo', icon: '☑' } },
  { path: '/ongoing', name: 'ongoing', component: () => import('../views/OngoingView.vue'), meta: { title: '进行中', icon: '◈' } },
  { path: '/media', name: 'media', component: () => import('../views/MediaView.vue'), meta: { title: '图书影视', icon: '▤' } },
  { path: '/stats', name: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '数据统计', icon: '◔' } },
  { path: '/settings', name: 'settings', component: () => import('../views/SettingsView.vue'), meta: { title: '设置', icon: '⚙' } }
]

const router = createRouter({ history: createWebHistory(), routes })

// 路由守卫：无 token 不能进业务页；已正式登录（游客除外）不再停留登录页
router.beforeEach((to) => {
  const authed = !!localStorage.getItem('ongoing.token')
  let guest = false
  try { guest = JSON.parse(localStorage.getItem('ongoing.user') || 'null')?.id === 'guest' } catch {}
  if (!to.meta.public && !authed) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }
  if (to.name === 'login' && authed && !guest) return { name: 'dashboard' }
  return true
})

export default router
