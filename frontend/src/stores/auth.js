import { defineStore } from 'pinia'
import { api } from '../api'

const TK = 'ongoing.token'
const US = 'ongoing.user'

function readUser() {
  try { return JSON.parse(localStorage.getItem(US) || 'null') } catch { return null }
}

/**
 * 登录态：token + 用户信息持久化在 localStorage。
 * 所有业务请求由 api 拦截器统一带 Authorization 头，401 时统一踢回 /login。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TK) || '',
    user: readUser()
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isGuest: (s) => s.user?.id === 'guest',
    displayName: (s) => s.user?.nickname || s.user?.username || ''
  },
  actions: {
    persist() {
      if (this.token) localStorage.setItem(TK, this.token)
      else localStorage.removeItem(TK)
      if (this.user) localStorage.setItem(US, JSON.stringify(this.user))
      else localStorage.removeItem(US)
    },

    // 应用启动时调用：有 token 则向后端校验并刷新用户信息；失效则清空
    async init() {
      if (!this.token) return false
      try {
        const res = await api.auth.me()
        this.user = { id: res.id, username: res.username, nickname: res.nickname, createdAt: res.createdAt }
        this.persist()
        return true
      } catch (e) {
        this.token = ''
        this.user = null
        this.persist()
        return false
      }
    },

    async login(username, password) {
      const r = await api.auth.login({ username, password })
      this.token = r.token
      this.user = r.user
      this.persist()
      return r.user
    },

    async register(payload) {
      const r = await api.auth.register(payload)
      this.token = r.token
      this.user = r.user
      this.persist()
      return r.user
    },

    // 游客体验登录：只读体验令牌，写请求本地模拟（不落库），要保存数据需注册/登录
    async loginAsGuest() {
      const r = await api.auth.guest()
      this.token = r.token
      this.user = r.user
      this.persist()
      return r.user
    },

    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TK)
      localStorage.removeItem(US)
    },

    setUser(user) {
      this.user = user
      this.persist()
    }
  }
})
