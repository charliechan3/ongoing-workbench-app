/**
 * 真机布局自检面板（只在 URL 带 #diag 时启用）。
 *
 * 为什么需要它：移动端「弹窗底部按钮被挡住 / 点不到」这类问题只在真机上暴露
 * （iOS Safari 会劫持 position:fixed 的包含块、软键盘只缩 visual viewport、
 *  浏览器工具栏盖住底部…），Chromium 里怎么模拟都正常。与其反复猜，不如让手机
 * 自己把数字报出来：截图一张即可定因。
 *
 * 用法：手机浏览器打开 `http://<局域网 IP>:5173/#diag`，复现问题后截图。
 * 面板 pointer-events:none，不挡任何操作；去掉 #diag 刷新即消失。
 */

const HUD_ID = 'dev-hud'

const box = (el) => {
  if (!el) return null
  const b = el.getBoundingClientRect()
  return { top: Math.round(b.top), bottom: Math.round(b.bottom), left: Math.round(b.left), right: Math.round(b.right), h: Math.round(b.height), w: Math.round(b.width) }
}

/** 从某元素往上找「会劫持 fixed 包含块」的祖先：可滚动，或带 -webkit-overflow-scrolling */
function hijackers(el) {
  const out = []
  let n = el ? el.parentElement : null
  while (n && n !== document.documentElement) {
    const cs = getComputedStyle(n)
    const scrollable = /(auto|scroll)/.test(cs.overflowY) || /(auto|scroll)/.test(cs.overflowX)
    const iosScroll = cs.getPropertyValue('-webkit-overflow-scrolling').trim() === 'touch'
    if (scrollable || iosScroll) {
      out.push(`${n.tagName.toLowerCase()}.${String(n.className).split(' ')[0]}`.slice(0, 28) + (iosScroll ? '[ios]' : '') + (scrollable ? '[scroll]' : ''))
    }
    n = n.parentElement
  }
  return out
}

function snapshot() {
  const vv = window.visualViewport
  const vvTop = vv ? Math.round(vv.offsetTop) : 0
  const vvH = vv ? Math.round(vv.height) : window.innerHeight
  const vvBottom = vvTop + vvH
  const hidden = Math.max(0, window.innerHeight - vvBottom)

  const mask = document.querySelector('.modal-mask')
  const footEl = document.querySelector('.modal-foot')
  const primary = document.querySelector('.modal-foot .btn.primary') || document.querySelector('.modal-foot .btn')
  const tabbar = document.querySelector('.sidebar')
  const target = primary || tabbar

  const maskBox = box(mask)
  const footBox = box(footEl)
  const targetBox = box(target)
  const targetName = primary ? '保存' : (tabbar ? '底栏' : '目标')
  const hijack = hijackers(mask)

  let hit = '-'
  if (targetBox) {
    const x = Math.min(window.innerWidth - 1, Math.max(0, Math.round((targetBox.left + targetBox.right) / 2)))
    const y = Math.min(window.innerHeight - 1, Math.max(0, Math.round((targetBox.top + targetBox.bottom) / 2)))
    const el = document.elementFromPoint(x, y)
    hit = el ? `${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]}`.slice(0, 30) : 'null'
  }

  const root = getComputedStyle(document.documentElement)
  const vars = ['--vvh', '--vvt', '--vvb'].map(k => root.getPropertyValue(k).trim() || '-').join(' / ')

  const warn = []
  if (targetBox && targetBox.bottom > vvBottom) warn.push(`${targetName}超出可见区`)
  if (targetBox && primary && hit !== 'null' && !hit.startsWith('button')) warn.push('按钮被别的元素盖住')
  if (hijack.length) warn.push('祖先劫持 fixed')
  if (!maskBox) warn.push('弹窗未打开')

  return (warn.length ? `⚠ ${warn.join(' / ')}\n` : '✓ 未见异常\n') + [
    `win ${window.innerHeight} · vv ${vvH} · vvTop ${vvTop} · 被遮 ${hidden} · scale ${vv ? vv.scale.toFixed(2) : '-'}`,
    `vars ${vars}`,
    maskBox ? `mask ${maskBox.top}→${maskBox.bottom}  h${maskBox.h}   祖先劫持: ${hijack.length ? hijack.join(' , ') : '无'}` : `mask: 未打开`,
    footBox ? `foot ${footBox.top}→${footBox.bottom}   可见区底 ${vvBottom}` : `foot: -`,
    targetBox ? `${targetName} ${targetBox.top}→${targetBox.bottom}  点中→ ${hit}` : `${targetName}: 未找到`,
    `dpr ${window.devicePixelRatio} · 屏幕 ${screen.width}x${screen.height} · ua ${navigator.userAgent.includes('iPhone') ? 'iPhone' : navigator.platform}`,
  ].join('\n')
}

export function initDevHud() {
  if (!/(^|[#&])diag/.test(location.hash)) return
  let el = document.getElementById(HUD_ID)
  if (!el) {
    el = document.createElement('div')
    el.id = HUD_ID
    el.style.cssText = [
      'position:fixed', 'left:0', 'right:0', 'top:0', 'z-index:99999',
      'background:rgba(20,12,32,.88)', 'color:#8ef7c0',
      'font:11px/1.45 ui-monospace,SFMono-Regular,Consolas,monospace',
      'padding:7px 9px', 'white-space:pre-wrap', 'word-break:break-all',
      'pointer-events:none', 'box-shadow:0 2px 10px rgba(0,0,0,.35)',
    ].join(';')
    document.body.appendChild(el)
  }
  const tick = () => { el.textContent = snapshot() }
  tick()
  setInterval(tick, 250)
  window.addEventListener('scroll', tick, true)
  window.addEventListener('resize', tick)
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', tick)
    window.visualViewport.addEventListener('scroll', tick)
  }
}
