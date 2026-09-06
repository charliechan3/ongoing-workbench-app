<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useDataStore } from './stores/data'
import { useAuthStore } from './stores/auth'

const store = useDataStore()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const cmdOpen = ref(false)
const cmdText = ref('')

const isAuthPage = computed(() => !!route.meta.public)

const navs = [
  { path: '/', label: '首页' },
  { path: '/todo', label: 'todo' },
  { path: '/ongoing', label: '进行中' },
  { path: '/media', label: '图书影视' },
  { path: '/stats', label: '数据统计' },
  { path: '/settings', label: '设置' }
]

// 先校验登录态：有效才加载业务数据并恢复番茄钟会话；失效则回登录页
onMounted(async () => {
  const ok = await auth.init()
  if (ok) {
    store.loadAll().then(() => store.restorePomoSession())
  } else if (!isAuthPage.value) {
    router.replace({ name: 'login' })
  }
})

// 登录页内完成登录/注册/游客体验后（SPA 切路由，onMounted 不会重跑）：
// token 变化 → 清空旧内存数据，重新拉取当前身份的数据。
// 注意不能在这里判断 isAuthPage 跳过：watcher 触发时路由还没切走（仍在 /login），
// 跳过会导致 loadAll 不执行——游客种子/新账号数据要等手动刷新才出现。
watch(() => auth.token, async (t) => {
  if (!t) return
  store.resetAll()
  await store.loadAll()
  store.restorePomoSession()
})

const today = () => {
  const d = new Date()
  return `${d.getMonth() + 1}月${d.getDate()}日 ${['周日','周一','周二','周三','周四','周五','周六'][d.getDay()]}`
}

// 全局快捷键：Ctrl/Cmd+K 打开搜索面板
const onKey = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') { e.preventDefault(); cmdOpen.value = !cmdOpen.value }
  if (e.key === 'Escape') cmdOpen.value = false
}
onMounted(() => window.addEventListener('keydown', onKey))

const quickItems = computed(() => {
  const q = cmdText.value.trim().toLowerCase()
  const all = [
    ...store.projects.map(p => ({ type: '项目', id: p.id, label: p.name, go: '/ongoing' })),
    ...store.tasks.map(t => ({ type: '任务', id: t.id, label: t.name, go: '/ongoing' })),
    ...store.medias.map(m => ({ type: m.type === 'book' ? '图书' : '影视', id: m.id, label: m.title, go: '/media' })),
    ...store.notes.map(n => ({ type: '笔记', id: n.id, label: n.title, go: '/ongoing' }))
  ].filter(x => !q || x.label.toLowerCase().includes(q)).slice(0, 8)
  return all
})

const displayName = computed(() => auth.displayName || '未登录')

function onLogout() {
  store.resetAll()          // 清空内存数据 & 停止番茄会话
  store.toast(auth.isGuest ? '已退出游客模式' : '已退出登录')
  auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-root">
    <!-- 业务布局（已登录） -->
    <div v-if="!isAuthPage" class="layout">
      <aside class="sidebar">
        <div class="brand" @click="router.push('/')">
          <div class="logo">ing</div>
          <div>
            <div class="brand-name">进行时-个人工作台</div>
            <div class="brand-sub">{{ today() }}</div>
          </div>
        </div>

        <nav class="nav">
          <div v-for="n in navs" :key="n.path"
               class="nav-item" :class="{ active: route.path === n.path }"
               @click="router.push(n.path)">
            <span class="nav-ico">{{ n.path === '/' ? '⌂' : n.path === '/todo' ? '☑' : n.path === '/ongoing' ? '◈' : n.path === '/media' ? '▤' : n.path === '/stats' ? '◔' : '⚙' }}</span>
            <span>{{ n.label }}</span>
            <span v-if="n.path === '/todo' && store.openTodos.length" class="nav-badge">{{ store.openTodos.length }}</span>
          </div>
        </nav>

        <!-- 当前登录用户 -->
        <div class="side-user">
          <div class="side-avatar">{{ (displayName || '?').slice(0, 1).toUpperCase() }}</div>
          <div class="grow truncate">
            <div class="side-name">{{ displayName }}</div>
            <div class="muted truncate">{{ auth.isGuest ? '游客模式 · 未保存数据' : '@' + (auth.user?.username || '') }}</div>
          </div>
          <button class="icon-btn" :title="auth.isGuest ? '退出游客模式' : '退出登录'" @click="onLogout">⎋</button>
        </div>
      </aside>

      <!-- 主区域 -->
      <div class="main">
        <!-- 游客模式提示条 -->
        <div v-if="auth.isGuest" class="guest-banner">
          <span class="guest-ico">👁</span>
          <span class="grow">游客模式：可自由体验，数据不会被保存；注册登录后才能保存数据。</span>
          <button class="guest-cta" @click="router.push('/login')">注册 / 登录</button>
        </div>
        <router-view v-slot="{ Component }">
          <component :is="Component" :key="route.path" />
        </router-view>
      </div>

      <!-- 快速创建面板 -->
      <div v-if="cmdOpen" class="modal-mask" @click.self="cmdOpen = false">
        <div class="modal cmd-panel" style="max-width:520px">
          <div class="cmd-input-wrap">
            <span class="cmd-ico">⌕</span>
            <input v-model="cmdText" class="cmd-input" placeholder="搜索项目 / 任务 / 笔记 / 影视…（Ctrl+K 切换）" autofocus />
          </div>
          <div class="cmd-list">
            <div v-if="!quickItems.length" class="empty" style="padding:24px">没有匹配结果</div>
            <div v-for="it in quickItems" :key="it.id" class="cmd-item" @click="router.push(it.go); cmdOpen = false">
              <span class="tag gray">{{ it.type }}</span>
              <span class="grow truncate">{{ it.label }}</span>
              <span class="muted">↵</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 登录 / 注册页（全屏） -->
    <router-view v-else />

    <!-- Toast（全局） -->
    <div class="toast-wrap">
      <div v-for="t in store.toasts" :key="t.id" class="toast" :class="t.type">{{ t.msg }}</div>
    </div>
  </div>
</template>

<style scoped>
.app-root { height: 100%; }
.sidebar {
  width: 232px; flex-shrink: 0;
  background: var(--surface);
  border-right: 1px solid var(--border);
  display: flex; flex-direction: column;
  padding: 18px 14px 12px;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 8px 18px; cursor: pointer; }
.logo {
  width: 38px; height: 38px; border-radius: 11px; flex-shrink: 0;
  background: linear-gradient(135deg, #bda6fe, #9b6dff);
  color: #fff; font-weight: 800; font-size: 19px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 3px 10px rgba(155, 109, 255, .35);
}
.brand-name { font-weight: 700; font-size: 14px; letter-spacing: -.2px; }
.brand-sub { font-size: 11px; color: var(--text-3); margin-top: 1px; }

.nav { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: 10px;
  color: var(--text-2); cursor: pointer; font-size: 13.5px; font-weight: 500;
  transition: all .13s ease;
}
.nav-item:hover { background: var(--surface-2); color: var(--text); }
.nav-item.active { background: var(--primary-soft); color: var(--primary); font-weight: 600; }
.nav-ico { width: 18px; text-align: center; font-size: 15px; opacity: .85; }
.nav-badge {
  margin-left: auto; min-width: 18px; height: 18px; border-radius: 999px;
  background: var(--primary); color: #fff; font-size: 10.5px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center; padding: 0 5px;
}

/* 侧栏底部用户区 */
.side-user {
  display: flex; align-items: center; gap: 9px;
  border-top: 1px solid var(--border); padding-top: 12px; margin-top: 4px;
}
.side-avatar {
  width: 32px; height: 32px; border-radius: 10px; flex-shrink: 0;
  background: linear-gradient(135deg, #c9b2ff, #9b6dff);
  color: #fff; font-weight: 700; font-size: 14px;
  display: flex; align-items: center; justify-content: center;
}
.side-name { font-size: 12.5px; font-weight: 600; line-height: 1.3; }

/* 命令面板 */
.cmd-panel { max-width: 520px; padding: 0; overflow: hidden; }

/* 游客模式提示条 */
.guest-banner {
  display: flex; align-items: center; gap: 10px;
  background: var(--primary-soft); border: 1px solid var(--primary-border, rgba(155, 109, 255, .35));
  color: var(--primary); border-radius: 12px;
  padding: 8px 14px; margin: 0 20px; font-size: 12.5px;
}
.guest-ico { font-size: 14px; }
.guest-cta {
  border: none; cursor: pointer; flex-shrink: 0;
  background: var(--primary); color: #fff;
  border-radius: 8px; padding: 4px 12px; font-size: 12px; font-weight: 600;
}
.guest-cta:hover { background: var(--primary-hover); }
.cmd-input-wrap { display: flex; align-items: center; gap: 10px; padding: 16px 18px; border-bottom: 1px solid var(--border); }
.cmd-ico { font-size: 17px; color: var(--text-3); }
.cmd-input { flex: 1; border: none; outline: none; font-size: 15px; font-family: inherit; color: var(--text); background: transparent; }
.cmd-list { padding: 8px; max-height: 320px; overflow-y: auto; }
.cmd-item { display: flex; align-items: center; gap: 10px; padding: 9px 10px; border-radius: 9px; cursor: pointer; }
.cmd-item:hover { background: var(--surface-2); }
</style>
