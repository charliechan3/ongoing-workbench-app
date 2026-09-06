// 一次性迁移：把 tasks/projects/goals/actions/todos 里的 legacy 优先级归一为 P1/P2/P3
const BASE = 'http://localhost:8080/api'
const MAP = { high: 'P1', '高': 'P1', medium: 'P2', mid: 'P2', '中': 'P2', low: 'P3', '低': 'P3', '普通': 'P3' }
const norm = (p) => {
  const v = String(p || '').trim()
  if (MAP[v]) return MAP[v]
  return ['P1', 'P2', 'P3'].includes(v) ? v : null
}
const RES = ['tasks', 'projects', 'goals', 'actions', 'todos']
let total = 0
for (const res of RES) {
  const list = await fetch(`${BASE}/${res}`).then(r => r.json())
  for (const item of list) {
    const np = norm(item.priority)
    if (np && np !== item.priority) {
      const put = await fetch(`${BASE}/${res}/${item.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...item, priority: np })
      })
      if (put.ok) { total++; console.log(`${res}/${item.id}: ${item.priority} -> ${np}`) }
      else console.error(`FAIL ${res}/${item.id}: HTTP ${put.status}`)
    }
  }
}
console.log(`done, migrated ${total} records`)
