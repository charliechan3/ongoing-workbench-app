/**
 * 「当前真正可见的视口」同步器。
 *
 * 解决什么问题：
 * 手机上点开任务/行动编辑这类长表单弹窗（底部抽屉）时，一旦软键盘弹出，
 * 固定在视口底部的「保存 / 取消」就被键盘压住，点不到。
 *
 * 为什么 CSS 自己搞不定：
 * · meta viewport 的 interactive-widget=resizes-content 只在 Chromium 生效，
 *   它会让布局视口跟着键盘缩小，于是 100dvh / position:fixed 都能正确避让；
 * · iOS Safari 不支持该取值，键盘弹出时**只缩小 visual viewport，不动布局视口**，
 *   底部的 fixed 元素就留在原位被键盘盖住。
 *
 * 做法：把 visualViewport 的几何写进 CSS 变量，由 CSS 让固定定位元素（弹窗遮罩、
 * Toast 等）按可见区域摆放，两端浏览器行为一致。不支持的浏览器不写变量，
 * CSS 里的 dvh / inset 兜底照旧生效（桌面端等于无操作）。
 *
 * 变量：
 *   --vvh  可见区高度（px）
 *   --vvt  可见区顶部相对布局视口的偏移（px，页面被顶起/双指平移时 > 0）
 *   --vvb  底部被遮挡的高度（px，即软键盘高度；键盘收起时为 0）
 */
export function initViewportVars() {
  const vv = window.visualViewport
  if (!vv) return

  let last = ''
  let raf = 0

  const apply = () => {
    raf = 0
    const h = Math.round(vv.height)
    const t = Math.round(Math.max(0, vv.offsetTop))
    // 布局视口底部 - 可见区底部 = 被键盘/候选栏/浏览器工具栏盖住的高度
    const b = Math.round(Math.max(0, window.innerHeight - vv.height - vv.offsetTop))
    const key = `${h}|${t}|${b}`
    if (key === last) return
    last = key
    const root = document.documentElement.style
    root.setProperty('--vvh', `${h}px`)
    root.setProperty('--vvt', `${t}px`)
    root.setProperty('--vvb', `${b}px`)
  }

  // 键盘弹出/收起、地址栏收放、双指缩放都会高频触发，合到一帧里处理
  const sync = () => { if (!raf) raf = requestAnimationFrame(apply) }

  apply()
  vv.addEventListener('resize', sync)
  vv.addEventListener('scroll', sync)
  window.addEventListener('orientationchange', () => { last = ''; sync() })
}
