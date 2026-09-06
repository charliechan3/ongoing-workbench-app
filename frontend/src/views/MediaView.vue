<script setup>
import { computed, ref } from 'vue'
import { useDataStore, today } from '../stores/data'
import Modal from '../components/Modal.vue'
import StatusBadge from '../components/StatusBadge.vue'
import ProgressBar from '../components/ProgressBar.vue'

const store = useDataStore()
const tab = ref('all')
const showModal = ref(false)
const form = ref({})
const editing = ref(null)

const typeMeta = {
  book: { label: '图书', cls: 'blue', ico: '📖' },
  movie: { label: '电影', cls: 'red', ico: '🎬' },
  tv: { label: '剧集', cls: 'green', ico: '📺' },
  doc: { label: '纪录片', cls: 'cyan', ico: '🎥' },
  other: { label: '其他', cls: 'gray', ico: '▤' }
}

const list = computed(() => {
  let l = [...store.medias].sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || ''))
  if (tab.value !== 'all') l = l.filter(m => m.type === tab.value)
  return l
})

const stats = computed(() => {
  const done = store.medias.filter(m => m.status === 'done')
  return {
    total: store.medias.length,
    reading: store.medias.filter(m => m.status === 'doing').length,
    done: done.length,
    avgRating: done.length ? (done.reduce((s, m) => s + (m.rating || 0), 0) / done.length).toFixed(1) : '—'
  }
})

function openCreate() {
  editing.value = null
  form.value = { type: 'book', status: 'wish', priority: 'P3', progress: 0, rating: 0 }
  showModal.value = true
}
function openEdit(m) {
  editing.value = m
  form.value = { ...m }
  showModal.value = true
}
async function save() {
  const f = { ...form.value }
  if (editing.value) await store.update('medias', editing.value.id, f)
  else await store.create('medias', f)
  showModal.value = false
}
async function del(m) {
  if (!confirm(`确定删除「${m.title}」？`)) return
  await store.remove('medias', m.id)
}
async function setStatus(m, s) {
  const patch = { status: s }
  if (s === 'done' && !m.endDate) patch.endDate = today()
  if (s !== 'done') patch.endDate = null
  await store.update('medias', m.id, patch, { silent: true })
}
</script>

<template>
  <div class="main-inner">
    <div class="page-head">
      <div>
        <div class="page-title">图书影视</div>
        <div class="page-sub">输入被消化，输出才有价值。读过的书、看过的片，都是你的知识弹药。</div>
      </div>
      <button class="btn primary" @click="openCreate">＋ 添加</button>
    </div>

    <!-- 统计 -->
    <div class="grid-4 mb-16">
      <div class="card stat-card"><div class="stat-ico" style="background:#f1ebff;color:#8a6add">▤</div><div><div class="stat-num">{{ stats.total }}</div><div class="muted">总收藏</div></div></div>
      <div class="card stat-card"><div class="stat-ico" style="background:#fef0e4;color:#dd5f0c">◔</div><div><div class="stat-num">{{ stats.reading }}</div><div class="muted">进行中</div></div></div>
      <div class="card stat-card"><div class="stat-ico" style="background:#e7f6ee;color:#2a8f5e">✓</div><div><div class="stat-num">{{ stats.done }}</div><div class="muted">已完成</div></div></div>
      <div class="card stat-card"><div class="stat-ico" style="background:#f1ebff;color:#9b6dff">★</div><div><div class="stat-num">{{ stats.avgRating }}</div><div class="muted">平均评分</div></div></div>
    </div>

    <!-- 类型切换 -->
    <div class="flex gap-8 mb-16" style="flex-wrap:wrap">
      <button class="chip" :class="{ on: tab === 'all' }" @click="tab = 'all'">全部</button>
      <button v-for="(m, k) in typeMeta" :key="k" class="chip" :class="{ on: tab === k }" @click="tab = k">{{ m.ico }} {{ m.label }}</button>
    </div>

    <!-- 列表 -->
    <div class="grid-3">
      <div v-for="m in list" :key="m.id" class="card media-card">
        <div class="flex-between mb-8">
          <span class="tag" :class="typeMeta[m.type]?.cls">{{ typeMeta[m.type]?.ico }} {{ typeMeta[m.type]?.label }}</span>
          <div class="flex gap-4">
            <span v-if="m.rating" class="star-line">★ {{ m.rating }}</span>
            <button class="icon-btn" @click="openEdit(m)">✎</button>
            <button class="icon-btn" @click="del(m)">🗑</button>
          </div>
        </div>
        <div style="font-weight:600;font-size:15px;margin-bottom:4px" class="truncate">{{ m.title }}</div>
        <div class="muted truncate mb-12" style="min-height:20px">{{ m.note || '暂无备注' }}</div>
        <ProgressBar :value="m.progress" :tone="m.progress >= 100 ? 'green' : ''" />
        <div class="flex-between mt-8">
          <span class="muted mono">{{ m.progress }}%</span>
          <span class="muted">{{ m.startDate || '' }} <template v-if="m.endDate">→ {{ m.endDate }}</template></span>
        </div>
        <div class="flex gap-4 mt-12" style="flex-wrap:wrap">
          <button v-for="s in ['wish', 'doing', 'done', 'paused', 'quit']" :key="s" class="mini-btn" :class="{ on: m.status === s }" @click="setStatus(m, s)">
            {{ s === 'wish' ? '想' : s === 'doing' ? '在看' : s === 'done' ? '完成' : s === 'paused' ? '暂停' : '放弃' }}
          </button>
        </div>
      </div>
      <div class="card new-card" @click="openCreate">
        <div class="new-plus">＋</div>
        <div class="muted">添加图书 / 影视</div>
      </div>
    </div>

    <!-- 编辑弹窗 -->
    <Modal v-if="showModal" :title="editing ? '编辑' : '添加'" @close="showModal = false">
      <div class="grid-2">
        <label class="field"><span>类型</span>
          <select v-model="form.type" class="select">
            <option v-for="(m, k) in typeMeta" :key="k" :value="k">{{ m.label }}</option>
          </select>
        </label>
        <label class="field"><span>状态</span>
          <select v-model="form.status" class="select">
            <option value="wish">想读/想看</option><option value="doing">进行中</option><option value="done">已完成</option><option value="paused">已暂停</option><option value="quit">已放弃</option>
          </select>
        </label>
      </div>
      <label class="field"><span>标题</span><input v-model="form.title" class="input" /></label>
      <label class="field"><span>备注 / 短评</span><textarea v-model="form.note" class="textarea" rows="3"></textarea></label>
      <div class="grid-2">
        <label class="field"><span>进度（%）</span><input v-model.number="form.progress" type="number" min="0" max="100" class="input" /></label>
        <label class="field"><span>评分（1-5）</span><input v-model.number="form.rating" type="number" min="0" max="5" class="input" /></label>
      </div>
      <div class="grid-2">
        <label class="field"><span>开始日期</span><input v-model="form.startDate" type="date" class="input" /></label>
        <label class="field"><span>完成日期</span><input v-model="form.endDate" type="date" class="input" /></label>
      </div>
      <template #foot>
        <button class="btn" @click="showModal = false">取消</button>
        <button class="btn primary" @click="save">保存</button>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.stat-card { display: flex; align-items: center; gap: 14px; padding: 16px 18px; }
.stat-ico { width: 42px; height: 42px; border-radius: 12px; display: flex; align-items: center; justify-content: center; font-size: 19px; flex-shrink: 0; }
.stat-num { font-size: 22px; font-weight: 700; line-height: 1.2; }

.chip { border: 1px solid var(--border); background: var(--surface); border-radius: 999px; padding: 6px 15px; font-size: 13px; cursor: pointer; color: var(--text-2); transition: all .13s; }
.chip:hover { border-color: var(--primary-border); color: var(--primary); }
.chip.on { background: var(--primary-soft); border-color: var(--primary-border); color: var(--primary); font-weight: 600; }

.media-card { display: flex; flex-direction: column; transition: all .18s; }
.media-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.star-line { color: var(--yellow); font-size: 12px; font-weight: 600; }
.mini-btn { border: 1px solid var(--border); background: var(--surface); border-radius: 7px; padding: 3px 9px; font-size: 11.5px; color: var(--text-2); cursor: pointer; }
.mini-btn:hover { border-color: var(--primary-border); }
.mini-btn.on { background: var(--primary); border-color: var(--primary); color: #fff; }
.new-card { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 200px; border-style: dashed; cursor: pointer; color: var(--text-3); transition: all .15s; }
.new-card:hover { border-color: var(--primary); color: var(--primary); background: var(--primary-soft); }
.new-plus { font-size: 26px; font-weight: 300; }
</style>
