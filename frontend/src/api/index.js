import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

function isGuest() {
  try { return JSON.parse(localStorage.getItem('ongoing.user') || 'null')?.id === 'guest' } catch { return false }
}

// 游客提示节流（写请求本地模拟成功时，只提示一次）
let guestWriteTipShown = false

// 请求统一携带 Bearer token（登录态存 localStorage）
http.interceptors.request.use((cfg) => {
  const t = localStorage.getItem('ongoing.token')
  if (t) cfg.headers.Authorization = 'Bearer ' + t
  // 游客模式：写请求不发后端，本地模拟成功（仅内存体验，刷新即失效，不落库）
  // 认证类接口除外（/auth/** 走真实后端，让游客感知到账号操作不可用）
  if (cfg.method !== 'get' && isGuest() && !String(cfg.url || '').startsWith('/auth/')) {
    const body = cfg.data || {}
    cfg.adapter = async () => ({
      data: cfg.method === 'delete' ? { ok: true } : body,
      status: 200, statusText: 'OK', headers: {}, config: cfg
    })
    if (!guestWriteTipShown) {
      guestWriteTipShown = true
      import('../stores/data').then(({ useDataStore }) =>
        useDataStore().toast('游客模式：更改仅临时保存，注册登录后才能保存数据', 'ok')
      ).catch(() => {})
    }
  }
  return cfg
})

// 401 统一处理：登录相关接口的错误交给页面展示；业务接口 401 = 登录过期 → 清 token 回登录页
// 403 兜底：游客令牌的写请求被后端拒绝（正常情况前端已本地模拟，不应到达这里）→ 提示注册登录
http.interceptors.response.use(
  (r) => r,
  (err) => {
    const url = err.config?.url || ''
    const status = err.response?.status
    if (status === 401 && !url.startsWith('/auth/')) {
      localStorage.removeItem('ongoing.token')
      localStorage.removeItem('ongoing.user')
      if (window.location.pathname !== '/login') window.location.assign('/login')
    }
    if (status === 403 && isGuest()) {
      import('../stores/data').then(({ useDataStore }) =>
        useDataStore().toast(err.response?.data?.message || '游客模式仅可体验浏览，注册登录后才能保存数据', 'err')
      ).catch(() => {})
    }
    return Promise.reject(err)
  }
)

// 通用 CRUD 工厂
const crud = (res) => ({
  list: () => http.get(`/${res}`).then(r => r.data),
  get: (id) => http.get(`/${res}/${id}`).then(r => r.data),
  create: (data) => http.post(`/${res}`, data).then(r => r.data),
  update: (id, data) => http.put(`/${res}/${id}`, data).then(r => r.data),
  remove: (id) => http.delete(`/${res}/${id}`).then(r => r.data)
})

export const api = {
  auth: {
    status: () => http.get('/auth/status').then(r => r.data),
    register: (data) => http.post('/auth/register', data).then(r => r.data),
    login: (data) => http.post('/auth/login', data).then(r => r.data),
    guest: () => http.post('/auth/guest').then(r => r.data),
    me: () => http.get('/auth/me').then(r => r.data),
    changePassword: (data) => http.put('/auth/password', data).then(r => r.data),
    updateProfile: (data) => http.put('/auth/profile', data).then(r => r.data)
  },
  areas: crud('areas'),
  projects: crud('projects'),
  tasks: crud('tasks'),
  actions: crud('actions'),
  todos: crud('todos'),
  checklist: crud('checklist'),
  pomodoros: crud('pomodoros'),
  worklogs: crud('worklogs'),
  notes: crud('notes'),
  medias: crud('media'),
  settings: {
    get: () => http.get('/settings').then(r => r.data),
    update: (data) => http.put('/settings', data).then(r => r.data)
  },
  backup: {
    export: () => http.get('/backup/export').then(r => r.data),
    exportMarkdown: () => http.get('/backup/export/markdown').then(r => r.data),
    preview: (bundle) => http.post('/backup/preview', bundle).then(r => r.data),
    import: (bundle, mode) => http.post(`/backup/import?mode=${mode}`, bundle).then(r => r.data)
  }
}

export default http
