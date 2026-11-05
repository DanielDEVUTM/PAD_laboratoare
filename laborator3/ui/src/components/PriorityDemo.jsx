import React, { useState } from 'react'
import { Card, Button } from './ui.jsx'
import { GATEWAY } from '../api.js'

/** Calls the gateway self-test: a burst of slow NORMAL requests followed by one HIGH request. */
export default function PriorityDemo() {
  const [res, setRes] = useState(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)

  const run = async () => {
    setBusy(true); setErr(null)
    try {
      const r = await fetch(`${GATEWAY}/admin/priority-tests?normal=30&delayMs=300`, { method: 'POST' })
      setRes(await r.json())
    } catch (e) { setErr(String(e)) } finally { setBusy(false) }
  }

  return (
    <Card title="Priority queue demo" right={<Button tone="primary" onClick={run} disabled={busy}>{busy ? 'Running…' : 'Run test'}</Button>}>
      <p className="text-xs text-slate-400">
        30 slow requests (300 ms each) saturate the gateway workers; 100 ms later one request with
        <code className="mx-1 rounded bg-slate-800 px-1">X-Priority: high</code> arrives.
      </p>
      {err && <p className="mt-2 text-xs text-rose-300">{err}</p>}
      {res && (
        <div className="mt-3 grid grid-cols-3 gap-2 text-center">
          <div className="rounded-lg bg-slate-800/50 p-2"><div className="mono text-lg text-amber-300">{res.normalRequests - (res.highFinishedPosition - 1)}/{res.normalRequests}</div><div className="text-[11px] text-slate-400">queued requests overtaken by HIGH (finished #{res.highFinishedPosition})</div></div>
          <div className="rounded-lg bg-slate-800/50 p-2"><div className="mono text-lg">{res.highFinishedAfterMs} ms</div><div className="text-[11px] text-slate-400">HIGH latency</div></div>
          <div className="rounded-lg bg-slate-800/50 p-2"><div className="mono text-lg">{res.lastNormalFinishedAfterMs} ms</div><div className="text-[11px] text-slate-400">last NORMAL finished</div></div>
        </div>
      )}
    </Card>
  )
}
