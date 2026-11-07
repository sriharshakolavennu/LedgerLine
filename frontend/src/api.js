export async function api(path, { method = 'GET', body, idem } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = sessionStorage.getItem('ll_token')
  if (token) headers.Authorization = 'Bearer ' + token
  if (idem) headers['Idempotency-Key'] = idem
  const r = await fetch('/api' + path, { method, headers, body: body ? JSON.stringify(body) : undefined })
  let data = null
  try { data = await r.json() } catch { /* empty body */ }
  if (!r.ok) {
    if (r.status === 401 && token) { sessionStorage.clear(); window.location.href = '/login' }
    const e = new Error(data?.message || 'Request failed (' + r.status + ')')
    e.code = data?.code || String(r.status)
    e.status = r.status
    throw e
  }
  return data
}

export const rupees = (p) =>
  '₹' + (p / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** "123.45" -> 12345 (integer paise). Returns null when invalid. No floating point maths on money. */
export function toPaise(s) {
  const m = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(String(s).trim())
  if (!m) return null
  const v = parseInt(m[1], 10) * 100 + parseInt((m[2] || '0').padEnd(2, '0'), 10)
  return v > 0 ? v : null
}

export const short = (h) => (h ? h.slice(0, 6) + '…' + h.slice(-4) : '')
export const newKey = () => crypto.randomUUID()
