import React from 'react'
import { Card, Badge } from './ui.jsx'

export default function ResponsePanel({ response }) {
  const r = response
  const tone = !r ? 'slate' : r.status >= 500 || r.status === 0 ? 'red' : r.status >= 400 ? 'amber' : 'green'
  let body = r?.text ?? ''
  if (r?.contentType?.includes('json')) {
    try { body = JSON.stringify(JSON.parse(r.text), null, 2) } catch { /* keep raw */ }
  }
  return (
    <Card title="Last response (raw)" right={r && <Badge tone={tone}>{r.status || 'ERR'}</Badge>}>
      {!r ? (
        <p className="text-sm text-slate-500">Perform an action to see the raw HTTP exchange.</p>
      ) : (
        <>
          <div className="mono text-xs text-slate-300">{r.method} {r.url}</div>
          <div className="mt-2 flex flex-wrap gap-1.5 text-[11px]">
            <Badge tone="slate">{r.ms} ms</Badge>
            {r.meta.servedBy && <Badge tone="blue">served by {r.meta.servedBy}</Badge>}
            {r.meta.cache && <Badge tone={r.meta.cache === 'HIT' ? 'green' : 'slate'}>cache {r.meta.cache}</Badge>}
            {r.meta.attempts && <Badge tone={r.meta.attempts > 1 ? 'amber' : 'slate'}>attempts {r.meta.attempts}</Badge>}
            {r.meta.priority && <Badge tone={r.meta.priority === 'HIGH' ? 'amber' : 'slate'}>priority {r.meta.priority}</Badge>}
            {r.contentType && <Badge tone="slate">{r.contentType.split(';')[0]}</Badge>}
          </div>
          {r.requestBody && (
            <>
              <div className="mt-3 text-[11px] uppercase tracking-wider text-slate-500">request body</div>
              <pre className="mt-1 max-h-24 overflow-auto rounded-md bg-slate-950 p-2 text-[11px] text-slate-300 whitespace-pre-wrap break-all">{r.requestBody}</pre>
            </>
          )}
          <div className="mt-3 text-[11px] uppercase tracking-wider text-slate-500">response body</div>
          <pre className="mt-1 max-h-72 overflow-auto rounded-md bg-slate-950 p-2 text-[11px] text-slate-300 whitespace-pre-wrap break-all">{body || '(empty)'}</pre>
        </>
      )}
    </Card>
  )
}
