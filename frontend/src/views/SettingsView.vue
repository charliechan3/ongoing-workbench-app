<script setup>
import { computed, onMounted, ref } from 'vue'
import { useDataStore, today } from '../stores/data'
import { useAuthStore } from '../stores/auth'
import { api } from '../api'
import Modal from '../components/Modal.vue'

const store = useDataStore()
const auth = useAuthStore()
const tab = ref('backup') // backup | pref | account

/* 后端存储模式：local（本地 H2 文件库）| cloud（云端数据库）。
   由 /auth/status 返回，用于「数据说明」里给出与实际一致的存储位置。 */
const serverMode = ref('local')
onMounted(async () => {
  try {
    const s = await api.auth.status()
    serverMode.value = s.mode === 'cloud' ? 'cloud' : 'local'
  } catch { /* 拿不到就按本地文案兜底 */ }
})

/* ===== 账号与安全 ===== */
const nickDraft = ref(auth.user?.nickname || '')
const oldPwd = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const nickMsg = ref('')
const pwdMsg = ref('')

async function saveNick() {
  nickMsg.value = ''
  const nick = nickDraft.value.trim()
  if (nick.length > 40) { nickMsg.value = '昵称不能超过 40 个字符'; return }
  try {
    const res = await api.auth.updateProfile({ nickname: nick })
    auth.setUser({ id: res.id, username: res.username, nickname: res.nickname, createdAt: res.createdAt })
    nickMsg.value = '✓ 已保存'
    store.toast('昵称已更新')
  } catch (e) {
    nickMsg.value = e.response?.data?.message || e.message || '保存失败'
  }
}

async function changePwd() {
  pwdMsg.value = ''
  if ((newPwd.value || '').length < 6) { pwdMsg.value = '新密码至少 6 位'; return }
  if (newPwd.value !== confirmPwd.value) { pwdMsg.value = '两次输入的新密码不一致'; return }
  try {
    await api.auth.changePassword({ oldPassword: oldPwd.value, newPassword: newPwd.value })
    pwdMsg.value = '✓ 密码已更新'
    oldPwd.value = ''; newPwd.value = ''; confirmPwd.value = ''
    store.toast('密码已更新')
  } catch (e) {
    pwdMsg.value = e.response?.data?.message || e.message || '修改失败'
  }
}

/* ===== 备份 ===== */
const previewResult = ref(null)
const previewBundle = ref(null)
const showPreview = ref(false)
const restoreMode = ref('overwrite')
const exporting = ref(false)

async function doExport() {
  exporting.value = true
  try {
    const data = await api.backup.export()
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `ongoing-workbench-backup-${today()}.json`
    a.click()
    URL.revokeObjectURL(a.href)
    store.toast('备份已导出')
  } finally { exporting.value = false }
}

async function doExportMarkdown() {
  const md = await api.backup.exportMarkdown()
  const blob = new Blob([md], { type: 'text/markdown' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `ongoing-workbench-${today()}.md`
  a.click()
  URL.revokeObjectURL(a.href)
  store.toast('Markdown 版已导出')
}

async function onImportFile(e) {
  const file = e.target.files?.[0]
  if (!file) return
  try {
    const text = await file.text()
    const bundle = JSON.parse(text)
    previewBundle.value = bundle
    previewResult.value = await api.backup.preview(bundle)
    showPreview.value = true
  } catch (err) {
    store.toast('导入文件解析失败，请确认是有效的备份 JSON', 'err')
  }
  e.target.value = ''
}

async function doRestore() {
  try {
    const r = await api.backup.import(previewBundle.value, restoreMode.value)
    await store.loadAll()
    showPreview.value = false
    store.toast(`恢复完成：${r.summary || ''}`)
  } catch (err) {
    store.toast('恢复失败：' + (err.response?.data?.message || err.message), 'err')
  }
}

/* ===== 偏好设置 ===== */
const pref = computed(() => store.settings || {})
async function savePref() {
  const s = store.settings
  // 番茄时长做前端兜底，后端字段名是 pomodoroMin（不是 pomoMinutes）
  const n = Number(s.pomodoroMin)
  s.pomodoroMin = Number.isFinite(n) && n >= 1 ? Math.round(n) : 25
  const saved = await api.settings.update(s)
  if (saved) store.settings = saved
  store.toast('偏好设置已保存')
}
</script>

<template>
  <div class="main-inner">
    <div class="page-head">
      <div>
        <div class="page-title">设置</div>
        <div class="page-sub">数据备份与工作偏好。</div>
      </div>
    </div>

    <div class="seg mb-16">
      <button class="seg-btn" :class="{ on: tab === 'backup' }" @click="tab = 'backup'">备份与恢复</button>
      <button class="seg-btn" :class="{ on: tab === 'pref' }" @click="tab = 'pref'">偏好设置</button>
      <button class="seg-btn" :class="{ on: tab === 'account' }" @click="tab = 'account'">账号与安全</button>
    </div>

    <!-- ===== 备份 ===== -->
    <template v-if="tab === 'backup'">
      <div class="grid-2" style="grid-template-columns:repeat(auto-fill, minmax(380px, 1fr))">
        <div class="card" style="display:flex;flex-direction:column">
          <h3 style="font-size:15px;margin-bottom:6px">导出备份</h3>
          <p class="muted mb-16">导出全部数据为 JSON 文件，或导出 Markdown 阅读版，方便随时查阅和留存。</p>
          <div class="flex gap-8" style="flex-wrap:wrap;margin-top:auto">
            <button class="btn primary" :disabled="exporting" @click="doExport">⬇ 导出 JSON 备份</button>
            <button class="btn" @click="doExportMarkdown">⬇ 导出 Markdown</button>
          </div>
        </div>

        <div class="card" style="display:flex;flex-direction:column">
          <h3 style="font-size:15px;margin-bottom:6px">导入恢复</h3>
          <p class="muted mb-16">选择备份文件后先预览检查，确认无误再执行恢复。</p>
          <div style="margin-top:auto">
            <label class="btn primary" style="cursor:pointer">
              ⬆ 选择备份文件
              <input type="file" accept=".json,application/json" hidden @change="onImportFile" />
            </label>
          </div>
        </div>
      </div>

      <div class="card mt-16">
        <h3 style="font-size:15px;margin-bottom:6px">数据说明</h3>
        <ul class="muted" style="padding-left:18px;line-height:2">
          <li v-if="serverMode === 'cloud'">数据存储在云端数据库（多设备登录同一账号即可共享），删除前端缓存不影响数据。</li>
          <li v-else>数据存储在后端 H2 文件数据库（<span class="mono">backend/data/workbench.mv.db</span>），删除前端缓存不影响数据。</li>
          <li>覆盖恢复将清空现有数据后整体替换；合并恢复保留现有数据，ID 冲突时自动为新记录生成新 ID 并重写关联。</li>
          <li>所有统计数据（番茄、工时、完成数）均从操作记录实时计算。</li>
        </ul>
      </div>
    </template>

    <!-- ===== 偏好 ===== -->
    <template v-else-if="tab === 'pref'">
      <div class="card" style="max-width:560px">
        <h3 style="font-size:15px;margin-bottom:16px">工作偏好</h3>
        <div class="pref-row">
          <div>
            <div style="font-weight:500">番茄时长（分钟）</div>
            <div class="muted">默认 25 分钟，完成时自动记录</div>
          </div>
          <input v-model.number="pref.pomodoroMin" type="number" min="5" max="120" class="input" style="width:90px" />
        </div>
        <div class="pref-row">
          <div>
            <div style="font-weight:500">每日工时目标（分钟）</div>
            <div class="muted">用于统计页对比实际工时</div>
          </div>
          <input v-model.number="pref.dailyGoalMinutes" type="number" min="0" max="1440" class="input" style="width:90px" />
        </div>
        <div class="pref-row">
          <div>
            <div style="font-weight:500">完成待办自动记番茄</div>
            <div class="muted">勾选后完成待办时自动沉淀 1 个番茄记录</div>
          </div>
          <input v-model="pref.autoPomoOnTodo" type="checkbox" class="switch" />
        </div>
        <div class="pref-row">
          <div>
            <div style="font-weight:500">完成行动自动记番茄</div>
            <div class="muted">勾选后完成行动时自动沉淀记录</div>
          </div>
          <input v-model="pref.autoPomoOnAction" type="checkbox" class="switch" />
        </div>
        <button class="btn primary mt-16" @click="savePref">保存偏好</button>
      </div>
    </template>

    <!-- ===== 账号 ===== -->
    <template v-else>
      <div class="grid-2" style="grid-template-columns:repeat(auto-fill, minmax(360px, 1fr))">
        <div class="card">
          <h3 style="font-size:15px;margin-bottom:6px">账号信息</h3>
          <p class="muted mb-16">用户名用于登录，创建后不可修改；昵称显示在工作台左下角。</p>
          <label class="field"><span>用户名</span>
            <input class="input" :value="auth.user?.username || ''" disabled />
          </label>
          <label class="field"><span>昵称</span>
            <input v-model="nickDraft" class="input" placeholder="显示在工作台左下角" @keyup.enter="saveNick" />
          </label>
          <div class="flex gap-8">
            <button class="btn primary" @click="saveNick">保存昵称</button>
            <span v-if="nickMsg" class="muted" style="color:var(--primary)">{{ nickMsg }}</span>
          </div>
        </div>

        <div class="card">
          <h3 style="font-size:15px;margin-bottom:6px">修改密码</h3>
          <p class="muted mb-16">修改后其他设备上的登录将失效，需用新密码重新登录。</p>
          <label class="field"><span>原密码</span>
            <input v-model="oldPwd" type="password" class="input" autocomplete="current-password" @keyup.enter="changePwd" />
          </label>
          <label class="field"><span>新密码</span>
            <input v-model="newPwd" type="password" class="input" placeholder="至少 6 位" autocomplete="new-password" @keyup.enter="changePwd" />
          </label>
          <label class="field"><span>确认新密码</span>
            <input v-model="confirmPwd" type="password" class="input" autocomplete="new-password" @keyup.enter="changePwd" />
          </label>
          <div class="flex gap-8">
            <button class="btn primary" @click="changePwd">更新密码</button>
            <span v-if="pwdMsg" class="muted" style="color:var(--primary)">{{ pwdMsg }}</span>
          </div>
        </div>
      </div>
      <div class="card mt-16" style="max-width:760px">
        <div class="flex-between">
          <div>
            <h3 style="font-size:15px;margin-bottom:6px">其他账号</h3>
            <p class="muted">在登录页选择「注册新账号」即可创建新的独立空间。</p>
          </div>
          <button class="btn" @click="auth.logout(); store.resetAll(); $router.push('/login')">退出当前账号</button>
        </div>
      </div>
    </template>

    <!-- 恢复预览弹窗 -->
    <Modal v-if="showPreview" title="恢复预览" wide @close="showPreview = false">
      <div v-if="previewResult">
        <div class="grid-4 mb-16">
          <div class="pv-card"><div class="pv-num">{{ previewResult.counts?.areas || 0 }}</div><div class="muted">区域</div></div>
          <div class="pv-card"><div class="pv-num">{{ previewResult.counts?.projects || 0 }}</div><div class="muted">项目</div></div>
          <div class="pv-card"><div class="pv-num">{{ previewResult.counts?.tasks || 0 }}</div><div class="muted">任务</div></div>
          <div class="pv-card"><div class="pv-num">{{ previewResult.counts?.actions || 0 }}</div><div class="muted">行动</div></div>
        </div>
        <div v-if="previewResult.conflicts?.length" class="warn-box">
          <strong>ID 冲突（{{ previewResult.conflicts.length }}）</strong>
          <div class="muted">这些记录将与现有数据冲突，合并模式下将自动换新 ID：</div>
          <div class="mono" style="font-size:12px">{{ previewResult.conflicts.slice(0, 8).join(', ') }}{{ previewResult.conflicts.length > 8 ? ' …' : '' }}</div>
        </div>
        <div v-if="previewResult.dangling?.length" class="warn-box">
          <strong>悬空关联（{{ previewResult.dangling.length }}）</strong>
          <div class="muted">备份中引用的父级不存在：</div>
          <div class="mono" style="font-size:12px">{{ previewResult.dangling.slice(0, 8).join(', ') }}{{ previewResult.dangling.length > 8 ? ' …' : '' }}</div>
        </div>
        <div v-if="!previewResult.conflicts?.length && !previewResult.dangling?.length" class="ok-box">✓ 检查通过，无 ID 冲突、无悬空关联</div>
      </div>
      <label class="field mt-16"><span>恢复模式</span>
        <select v-model="restoreMode" class="select">
          <option value="overwrite">覆盖恢复（清空现有数据，完整替换）</option>
          <option value="merge">合并恢复（保留现有数据，冲突自动换 ID）</option>
        </select>
      </label>
      <template #foot>
        <button class="btn" @click="showPreview = false">取消</button>
        <button class="btn primary" @click="doRestore">执行恢复</button>
      </template>
    </Modal>

  </div>
</template>

<style scoped>
.seg { display: inline-flex; background: var(--surface-2); border-radius: 10px; padding: 3px; }
.seg-btn { border: none; background: transparent; padding: 6px 16px; border-radius: 8px; font-size: 13px; color: var(--text-2); cursor: pointer; }
.seg-btn.on { background: var(--surface); color: var(--primary); font-weight: 600; box-shadow: var(--shadow-sm); }

.pref-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 13px 0; border-bottom: 1px solid var(--border); }
.pref-row:last-child { border-bottom: none; }
.switch { width: 38px; height: 21px; appearance: none; background: var(--border-strong); border-radius: 999px; position: relative; cursor: pointer; transition: background .15s; flex-shrink: 0; }
.switch:checked { background: var(--primary); }
.switch::after { content: ''; position: absolute; top: 2px; left: 2px; width: 17px; height: 17px; border-radius: 50%; background: #fff; transition: left .15s; }
.switch:checked::after { left: 19px; }

.pv-card { background: var(--surface-2); border-radius: 10px; padding: 12px; text-align: center; }
.pv-num { font-size: 22px; font-weight: 700; }
.warn-box { background: #fef5e7; border: 1px solid #f5d9a8; border-radius: 10px; padding: 12px 14px; margin-top: 10px; font-size: 13px; }
.ok-box { background: #e7f6ee; border: 1px solid #b8e3cd; border-radius: 10px; padding: 12px 14px; margin-top: 10px; font-size: 13px; color: #2a8f5e; }
</style>
