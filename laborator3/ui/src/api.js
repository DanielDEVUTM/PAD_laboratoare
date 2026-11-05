// Everything goes through the gateway: the UI knows only its address, never the Data Warehouse nodes.
export const GATEWAY =
  window.__GATEWAY_URL__ || import.meta.env.VITE_GATEWAY_URL || 'http://localhost:8000'

const esc = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

export function toXml(obj, root = 'employee') {
  const inner = Object.entries(obj)
    .filter(([, v]) => v !== '' && v !== null && v !== undefined)
    .map(([k, v]) => `<${k}>${esc(v)}</${k}>`)
    .join('')
  return `<${root}>${inner}</${root}>`
}

/**
 * Performs a request against the gateway.
 * options: format ('json'|'xml' - request AND response format), priority ('low'|'normal'|'high'),
 *          noCache, body (object), headers
 */
export async function call(method, path, opts = {}) {
  const { format = 'json', priority = 'normal', noCache = false, body, headers = {} } = opts
  const url = new URL(GATEWAY + path)
  if (format === 'xml') url.searchParams.set('format', 'xml')
  const h = { ...headers }
  if (priority !== 'normal') h['X-Priority'] = priority
  if (noCache) h['Cache-Control'] = 'no-cache'
  let payload
  if (body !== undefined) {
    h['Content-Type'] = format === 'xml' ? 'application/xml' : 'application/json'
    payload = format === 'xml' ? toXml(body) : JSON.stringify(body)
  }
  const t0 = performance.now()
  const res = await fetch(url, { method, headers: h, body: payload })
  const text = await res.text()
  return {
    method,
    url: url.pathname + url.search,
    requestBody: payload,
    status: res.status,
    ok: res.ok,
    ms: Math.round(performance.now() - t0),
    text,
    contentType: res.headers.get('Content-Type'),
    meta: {
      servedBy: res.headers.get('X-Served-By'),
      cache: res.headers.get('X-Cache'),
      attempts: res.headers.get('X-Attempts'),
      priority: res.headers.get('X-Priority-Applied'),
    },
  }
}

export async function getJson(path) {
  const res = await fetch(GATEWAY + path)
  if (!res.ok) throw new Error(`${path} -> HTTP ${res.status}`)
  return res.json()
}
