<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useDataStore } from '../stores/data'
import { api } from '../api'

const auth = useAuthStore()
const store = useDataStore()
const route = useRoute()
const router = useRouter()

// 首次打开时向后端询问系统是否已初始化：
// 未初始化 → 引导创建首个账号（该账号自动认领升级前的历史数据）
const initChecked = ref(false)
const firstRun = ref(false)
const serverMode = ref('local') // local（本地 H2）| cloud（云端数据库），由后端 /auth/status 返回
const mode = ref('login') // login | register
const busy = ref(false)
const errMsg = ref('')

const form = ref({ username: '', password: '', nickname: '', confirm: '' })

onMounted(async () => {
  try {
    const s = await api.auth.status()
    firstRun.value = !s.initialized
    serverMode.value = s.mode === 'cloud' ? 'cloud' : 'local'
    if (firstRun.value) mode.value = 'register'
  } catch {
    errMsg.value = '无法连接后端服务，请确认已启动（端口 8080）'
  } finally { initChecked.value = true }
})

// 游客体验登录：只读体验令牌，可自由浏览试用，但数据不会被保存
async function guest() {
  errMsg.value = ''
  busy.value = true
  try {
    await auth.loginAsGuest()
    store.toast('游客模式：可自由体验，数据不会被保存；注册登录后才能保存数据', 'ok')
    afterSuccess()
  } catch (e) {
    errMsg.value = e.response?.data?.message || e.message || '进入游客模式失败，请重试'
  } finally { busy.value = false }
}

function switchMode(m) {
  mode.value = m
  errMsg.value = ''
  form.value.password = ''
  form.value.confirm = ''
}

function afterSuccess() {
  const target = route.query.redirect && String(route.query.redirect).startsWith('/') ? String(route.query.redirect) : '/'
  router.replace(target)
}

async function submit() {
  errMsg.value = ''
  const f = form.value
  if (mode.value === 'register') {
    if (f.username.trim().length < 2) return (errMsg.value = '用户名至少 2 个字符')
    if (f.nickname.trim() && f.nickname.trim().length > 40) return (errMsg.value = '昵称不超过 40 个字符')
    if ((f.password || '').length < 6) return (errMsg.value = '密码至少 6 位')
    if (f.password !== f.confirm) return (errMsg.value = '两次输入的密码不一致')
  } else {
    if (!f.username.trim() || !f.password) return (errMsg.value = '请输入用户名和密码')
  }
  busy.value = true
  try {
    if (mode.value === 'register') {
      await auth.register({ username: f.username.trim(), password: f.password, nickname: f.nickname.trim() || undefined })
    } else {
      await auth.login(f.username.trim(), f.password)
    }
    afterSuccess()
  } catch (e) {
    errMsg.value = e.response?.data?.message || e.message || '操作失败，请重试'
  } finally { busy.value = false }
}
</script>

<template>
  <div class="auth-wrap">
    <div class="auth-card">
      <!-- 品牌侧 -->
      <div class="brand-side">
        <div class="auth-logo">ing</div>
        <div class="auth-title">进行时-个人工作台</div>
        <div class="auth-sub">个人事业规划 · 番茄专注 · 复盘沉淀</div>
        <ul class="auth-points">
          <li>✓ 区域 → 项目 → 任务 → 行动 层级规划</li>
          <li>✓ 待办清单 + 月历视图 + 重复规则</li>
          <li>
        {{ serverMode === 'cloud'
          ? '✓ 数据云端存储，可实现多设备共享'
          : '✓ 数据按账号隔离，可多人共用工作台' }}
          </li>
        </ul>
      </div>

      <!-- 表单侧 -->
      <div class="form-side">
        <div v-if="!initChecked" class="muted" style="text-align:center;padding:40px 0">正在连接…</div>
        <template v-else>
          <div class="auth-mode">
            <button class="mode-btn" :class="{ on: mode === 'login' }" :disabled="firstRun" @click="switchMode('login')">登录</button>
            <button class="mode-btn" :class="{ on: mode === 'register' }" @click="switchMode('register')">注册新账号</button>
          </div>

          <div v-if="firstRun && mode === 'register'" class="first-tip">
            首次使用：创建首个账号后，将自动认领工作台现有的全部数据。
          </div>

          <div class="fields">
            <label class="field">
              <span>用户名</span>
              <input v-model.trim="form.username" class="input" :class="{ err: errMsg }" autocomplete="username"
                     placeholder="用于登录的账号名" @keyup.enter="submit" />
            </label>
            <label v-if="mode === 'register'" class="field">
              <span>昵称（可选，显示在工作台）</span>
              <input v-model.trim="form.nickname" class="input" placeholder="如：Charlie" />
            </label>
            <label class="field">
              <span>密码</span>
              <input v-model="form.password" type="password" class="input" autocomplete="current-password"
                     placeholder="至少 6 位" @keyup.enter="submit" />
            </label>
            <label v-if="mode === 'register'" class="field">
              <span>确认密码</span>
              <input v-model="form.confirm" type="password" class="input" autocomplete="new-password"
                     placeholder="再次输入密码" @keyup.enter="submit" />
            </label>
          </div>

          <div v-if="errMsg" class="auth-err">⚠ {{ errMsg }}</div>

          <button class="btn primary submit" :disabled="busy" @click="submit">
            {{ busy ? '请稍候…' : mode === 'register' ? '创建账号并进入' : '登 录' }}
          </button>

          <div class="guest-area">
            <button class="btn ghost guest-btn" :disabled="busy" @click="guest">先逛逛 · 游客体验</button>
            <div class="guest-tip">游客模式仅可体验浏览，数据不会被保存；要保存数据请注册或登录。</div>
          </div>
        </template>
      </div>
    </div>
    <div class="auth-foot">
      {{ serverMode === 'cloud'
        ? '数据同步到云端，多设备登录同一账号即可共享数据。'
        : '数据保存在本地 H2 数据库；登录仅用于保护你的个人空间。' }}
    </div>
  </div>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 18px;
  background:
    radial-gradient(1000px 500px at 85% -10%, rgba(155, 109, 255, 0.25), transparent 60%),
    radial-gradient(800px 420px at -10% 110%, rgba(155, 109, 255, 0.18), transparent 55%),
    var(--bg);
  padding: 24px;
}
.auth-card {
  width: 100%; max-width: 780px; min-height: 480px;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: 22px; box-shadow: var(--shadow-lg);
  display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr); overflow: hidden;
}
.brand-side {
  background: linear-gradient(150deg, #b48cff, #9b6dff 55%, #7d4ff0);
  color: #fff; padding: 42px 38px;
  display: flex; flex-direction: column;
}
.auth-logo {
  width: 52px; height: 52px; border-radius: 15px;
  background: rgba(255, 255, 255, 0.22); backdrop-filter: blur(2px);
  display: flex; align-items: center; justify-content: center;
  font-size: 26px; font-weight: 800; margin-bottom: 22px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.14);
}
.auth-title { font-size: 24px; font-weight: 800; letter-spacing: -0.3px; }
.auth-sub { font-size: 13px; opacity: 0.85; margin-top: 6px; }
.auth-points { list-style: none; margin-top: 34px; display: flex; flex-direction: column; gap: 10px; font-size: 13px; opacity: 0.92; }
.form-side { padding: 34px 34px 26px; display: flex; flex-direction: column; justify-content: center; }
.auth-mode { display: flex; gap: 8px; background: var(--surface-2); padding: 3px; border-radius: 10px; margin-bottom: 18px; }
.mode-btn {
  flex: 1; border: none; background: transparent; padding: 7px 0;
  border-radius: 8px; font-size: 13px; color: var(--text-2); cursor: pointer;
}
.mode-btn.on { background: var(--surface); color: var(--primary); font-weight: 600; box-shadow: var(--shadow-sm); }
.mode-btn:disabled { cursor: not-allowed; opacity: .7; }
.first-tip {
  background: var(--primary-soft); border: 1px solid var(--primary-border);
  color: var(--primary); border-radius: 10px; padding: 9px 12px;
  font-size: 12.5px; margin-bottom: 16px;
}
.fields { display: flex; flex-direction: column; gap: 2px; }
.auth-err { background: #fdecec; border: 1px solid #f5c2c4; color: var(--red); font-size: 12.5px; border-radius: 9px; padding: 8px 12px; margin: 4px 0 12px; }
.submit { width: 100%; justify-content: center; padding: 10px 0; font-size: 14px; margin-top: 6px; }
.auth-foot { font-size: 12px; color: var(--text-3); text-align: center; }
.input.err { border-color: var(--red); }
.guest-area { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.guest-btn { width: 100%; justify-content: center; padding: 9px 0; font-size: 13.5px; color: var(--primary); border: 1px dashed var(--primary-border, rgba(155, 109, 255, .4)); border-radius: 10px; }
.guest-tip { font-size: 11.5px; color: var(--text-3); text-align: center; line-height: 1.5; }

/* ===================== 移动端（≤ 720px） ===================== */
@media (max-width: 720px), (pointer: coarse) and (max-height: 480px) {
  .auth-wrap {
    padding: 16px 14px 22px;
    gap: 14px;
    /* 手机上内容可能高于一屏（横屏 / 小屏），允许滚动并把卡片居中，避免顶部被裁掉 */
    overflow-y: auto;
    -webkit-overflow-scrolling: touch;
  }
  .auth-card {
    grid-template-columns: 1fr;
    min-height: 0;
    border-radius: 18px;
    margin-block: auto;
    flex-shrink: 0;
  }
  /* 品牌侧不整体隐藏，改为「紧凑头部」：保留 logo / 标题 / 副标题建立身份感，
     只收起三条功能要点（它们在登录场景里信息价值低，却要占掉近半屏高度） */
  .brand-side { padding: 22px 20px 18px; }
  .auth-logo { width: 42px; height: 42px; border-radius: 12px; font-size: 21px; margin-bottom: 14px; }
  .auth-title { font-size: 20px; }
  .auth-sub { font-size: 12.5px; margin-top: 4px; }
  .auth-points { display: none; }

  .form-side { padding: 20px 20px 22px; }
  .mode-btn { padding: 11px 0; }
  .submit { padding: 13px 0; }
  .guest-btn { padding: 13px 0; }
  .auth-foot { padding: 0 4px; }
}

/* 小屏精修（≤ 430px）：进一步压缩品牌头部，让表单更早出现在首屏 */
@media (max-width: 430px) {
  .auth-wrap { padding: 12px 11px 18px; gap: 10px; }
  .auth-card { border-radius: 16px; }
  .brand-side { padding: 18px 16px 14px; }
  .auth-logo { width: 38px; height: 38px; font-size: 19px; margin-bottom: 12px; }
  .auth-title { font-size: 18px; }
  .form-side { padding: 18px 16px 20px; }
}
</style>
