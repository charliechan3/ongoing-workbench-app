import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // 监听所有网卡：允许手机/局域网其它设备通过 http://<本机IP>:5173 访问（多端同步场景）
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 手机用 http://<局域网IP>:5173 打开时，浏览器的 POST/PUT/DELETE 会带上
        // Origin: http://192.168.x.x:5173。changeOrigin 只改写 Host、不改 Origin，
        // 后端 CORS 白名单（默认只有 localhost:5173 / 127.0.0.1:5173）于是判成
        // 403 "Invalid CORS request" —— 症状是页面能开、数据能读，一登录/一保存就 403。
        // 代理转发前摘掉 Origin，请求在后端看来就是同源直连，CORS 不再介入。
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        }
      }
    }
  }
})
