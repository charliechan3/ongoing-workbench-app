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

// 导航表：label 为桌面侧栏文案，short 为移动端底部标签栏文案（窄屏放不下 4 个字的长词）
const navs = [
  { path: '/', label: '首页', short: '首页' },
  { path: '/todo', label: 'todo', short: '待办' },
  { path: '/ongoing', label: '进行中', short: '进行' },
  { path: '/media', label: '图书影视', short: '阅读' },
  { path: '/stats', label: '数据统计', short: '统计' },
  { path: '/settings', label: '设置', short: '设置' }
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
            <span class="nav-txt">{{ n.label }}</span>
            <span class="nav-txt-sm">{{ n.short }}</span>
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
        <!-- 移动端顶栏（桌面端 display:none）：桌面侧栏在手机上收成底部标签栏后，
             品牌信息与「当前是谁在用 / 去哪里退出」需要有新的落点，统一放这里 -->
        <header class="mob-bar">
          <div class="mob-brand" @click="router.push('/')">
            <div class="mob-logo">ing</div>
            <div class="grow truncate">
              <div class="mob-title">进行时 · 个人工作台</div>
              <div class="mob-sub">{{ today() }}</div>
            </div>
          </div>
          <button class="mob-icon" title="搜索项目 / 任务 / 笔记 / 影视" @click="cmdOpen = true">⌕</button>
          <button class="mob-user" :title="auth.isGuest ? '游客模式' : '账号与安全'" @click="router.push('/settings')">
            <span class="mob-avatar">{{ (displayName || '?').slice(0, 1).toUpperCase() }}</span>
            <span class="mob-user-name truncate">{{ displayName }}</span>
          </button>
        </header>

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
        <div class="modal cmd-panel">
          <div class="cmd-input-wrap">
            <span class="cmd-ico">⌕</span>
            <input v-model="cmdText" class="cmd-input" placeholder="搜索项目 / 任务 / 笔记 / 影视…" autofocus />
            <!-- 键盘快捷键提示：桌面端可见，移动端隐藏（手机上由顶栏的搜索按钮进入） -->
            <span class="kbd-hint">Ctrl K</span>
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
/* 短标签仅移动端可见，桌面沿用完整 label（见文件末尾的媒体查询） */
.nav-txt-sm { display: none; }
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

/* ---- 移动端顶栏：桌面端完全隐藏，不占位 ---- */
.mob-bar { display: none; }
.mob-brand { display: flex; align-items: center; gap: 9px; min-width: 0; flex: 1; cursor: pointer; }
.mob-logo {
  width: 30px; height: 30px; border-radius: 9px; flex-shrink: 0;
  background: linear-gradient(135deg, #bda6fe, #9b6dff);
  color: #fff; font-weight: 800; font-size: 15px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 8px rgba(155, 109, 255, .32);
}
.mob-title { font-weight: 700; font-size: 13.5px; line-height: 1.25; }
.mob-sub { font-size: 11px; color: var(--text-3); line-height: 1.3; }
.mob-user {
  display: flex; align-items: center; gap: 6px; flex-shrink: 0;
  max-width: 42%; min-height: 38px;
  border: 1px solid var(--border); background: var(--surface);
  border-radius: 999px; padding: 3px 12px 3px 4px;
  cursor: pointer; font-family: inherit; color: var(--text);
}
.mob-user:active { background: var(--surface-2); }
.mob-avatar {
  width: 24px; height: 24px; border-radius: 50%; flex-shrink: 0;
  background: linear-gradient(135deg, #c9b2ff, #9b6dff);
  color: #fff; font-weight: 700; font-size: 12px;
  display: flex; align-items: center; justify-content: center;
}
.mob-user-name { font-size: 12px; font-weight: 600; }
.mob-icon {
  flex-shrink: 0; width: 38px; height: 38px; border-radius: 11px;
  border: 1px solid var(--border); background: var(--surface);
  color: var(--text-2); font-size: 18px; line-height: 1;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; font-family: inherit;
}
.mob-icon:active { background: var(--surface-2); color: var(--primary); }
/* 快捷键提示标签（桌面端搜索面板内显示） */
.kbd-hint {
  flex-shrink: 0; font-size: 11px; color: var(--text-3);
  border: 1px solid var(--border); border-radius: 6px; padding: 2px 7px; background: var(--surface-2);
}

/* =====================================================================
   移动端（≤ 820px）：侧栏由左侧固定栏改为底部标签栏
   ---------------------------------------------------------------------
   为什么用底部标签栏而不是抽屉：
   本应用的六个入口是「平级、高频、互斥」的主导航，底部 tab 最符合移动端心智，
   且无需任何 JS 状态（不引入抽屉开关、返回键拦截等额外复杂度）。
   侧栏原有的品牌区与用户区在手机上隐藏，改由 .mob-bar 承担。
   ===================================================================== */
@media (max-width: 820px), (pointer: coarse) and (max-height: 480px) {
  .sidebar {
    position: fixed;
    left: 0; right: 0; bottom: 0; top: auto;
    width: 100%; height: auto;
    flex-direction: row;
    padding: 6px 4px calc(6px + env(safe-area-inset-bottom, 0px));
    border-right: none;
    border-top: 1px solid var(--border);
    box-shadow: 0 -6px 22px rgba(45, 35, 64, .09);
    z-index: 90;
  }
  .brand, .side-user { display: none; }

  /* 标签栏内容限宽居中：平板宽度下六个 tab 铺满整屏会显得很散 */
  .nav { flex-direction: row; gap: 0; flex: 1; max-width: 560px; margin: 0 auto; }
  .nav-item {
    flex: 1 1 0; min-width: 0;
    flex-direction: column; justify-content: center; text-align: center;
    gap: 2px; padding: 6px 2px; border-radius: 12px;
    font-size: 10.5px; line-height: 1.2;
    position: relative;
  }
  /* 图标作为选中态的主要视觉载体（底部 tab 的常规做法），文字只做标识 */
  .nav-ico { width: auto; font-size: 18px; line-height: 1; opacity: .7; }
  .nav-item.active .nav-ico { opacity: 1; }
  .nav-txt { display: none; }
  .nav-txt-sm { display: inline; }
  /* 待办角标：绝对定位到图标右上角，不再参与横向排版 */
  .nav-badge {
    position: absolute; top: 2px; left: 50%; margin-left: 6px; margin-right: 0;
    min-width: 15px; height: 15px; font-size: 9.5px; padding: 0 4px;
  }

  .mob-bar {
    display: flex; align-items: center; gap: 10px;
    padding: 8px 12px;
    background: var(--surface);
    border-bottom: 1px solid var(--border);
    z-index: 80;
  }

  .guest-banner { margin: 8px 12px 0; padding: 8px 10px; font-size: 12px; gap: 8px; }
  .guest-cta { padding: 6px 12px; min-height: 32px; }

  .cmd-input { font-size: 16px; }
  .cmd-item { padding: 11px 10px; }
  .cmd-list { max-height: 46dvh; }
  /* 手机上不存在 Ctrl+K，提示标签只会造成困惑（入口改为顶栏的搜索按钮） */
  .kbd-hint { display: none; }
}

/* 小屏精修：顶栏收紧，用户区只留头像 */
@media (max-width: 430px) {
  .mob-bar { padding: 7px 11px; }
  .mob-user { padding: 3px 9px 3px 4px; gap: 0; min-width: 40px; }
  .mob-user-name { display: none; }
  .mob-user { max-width: none; }
  .nav-item { font-size: 10px; }
  .nav-ico { font-size: 17px; }
}

/* 手机横屏（粗指针 + 矮视口）：顶栏与底部标签栏压薄，把竖向空间让给内容。
   这些元素都在本组件内、带 scoped 属性选择器，全局样式覆盖不到，故写在组件里。 */
@media (pointer: coarse) and (max-height: 480px) {
  .mob-bar { padding: 5px 14px; }
  .mob-logo { width: 26px; height: 26px; border-radius: 8px; font-size: 13px; }
  .mob-title { font-size: 12.5px; }
  .mob-sub { display: none; }          /* 日期在横屏次要，省一行高度 */
  .mob-icon { width: 38px; height: 38px; }
  .mob-user { padding: 3px 10px 3px 4px; }
  .sidebar { padding: 4px 10px calc(4px + env(safe-area-inset-bottom, 0px)); }
  .nav { max-width: 720px; }
  .nav-item { padding: 4px 2px; gap: 1px; }
  .nav-ico { font-size: 17px; }
  .guest-banner { margin: 6px 14px 0; }
  .cmd-list { max-height: 56dvh; }
}
</style>
