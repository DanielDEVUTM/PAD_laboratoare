import React, { useCallback, useEffect, useState } from 'react'
import { Card, Badge, Button } from './ui.jsx'
import { manage } from '../api.js'

const tone = { running: 'green', stopped: 'red', frozen: 'amber' }

function Component({ label, c, onState, busy }) {
  if (!c) return <span className="text-xs text-slate-600">—</span>
  return (
    <div className="flex items-center gap-1.5">
      <span className="w-7 text-[11px] uppercase text-slate-500">{label}</span>
      <Badge tone={tone[c.state]}>{c.state}</Badge>
      <Button disabled={busy || c.state === 'running'} onClick={() => onState('running')}>Start</Button>
      <Button disabled={busy || c.state === 'stopped'} onClick={() => onState('stopped')}>Stop</Button>
      <Button disabled={busy || c.state !== 'running'} onClick={() => onState('frozen')}>Freeze</Button>
    </div>
  )
}

/** Create / remove / stop / freeze warehouses (API + its own database) at runtime. */
export default function WarehousePanel({ tick }) {
  const [list, setList] = useState([])
  const [name, setName] = useState('')
  const [count, setCount] = useState(1)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try { setList(await manage('GET', '/warehouses')); setError(null) } catch (e) { setError(String(e.message)) }
  }, [])

  useEffect(() => { load() }, [load, tick])

  const run = async (fn) => {
    setBusy(true)
    try { await fn(); setError(null) } catch (e) { setError(String(e.message)) } finally { setBusy(false); load() }
  }

  const create = (e) => {
    e.preventDefault()
    run(async () => {
      await manage('POST', '/warehouses', name.trim() ? { name: name.trim() } : { count: Number(count) })
      setName('')
    })
  }

  return (
    <Card
      title="Warehouses (API + own database) — create as many as you want"
      right={<Badge tone="slate">{list.length} warehouse{list.length === 1 ? '' : 's'}</Badge>}
    >
      <form onSubmit={create} className="mb-4 flex flex-wrap items-end gap-3 rounded-lg border border-slate-800 bg-slate-950/50 p-3">
        <label className="flex flex-col gap-0.5 text-[11px] text-slate-400">
          name (optional, e.g. paris)
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="auto: wh-N"
            className="rounded-md border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100" />
        </label>
        <label className="flex flex-col gap-0.5 text-[11px] text-slate-400">
          how many
          <input type="number" min="1" max="10" value={count} disabled={!!name.trim()}
            onChange={(e) => setCount(e.target.value)}
            className="w-20 rounded-md border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100 disabled:opacity-40" />
        </label>
        <Button tone="primary" type="submit" disabled={busy}>+ Create warehouse</Button>
        <span className="text-[11px] text-slate-500">starts a new MongoDB member (data is copied automatically) and a new API that registers itself in the gateway</span>
      </form>

      {error && <p className="mb-2 text-xs text-rose-300">{error}</p>}

      <div className="overflow-auto rounded-lg border border-slate-800">
        <table className="w-full text-left text-xs">
          <thead className="bg-slate-900 text-slate-400">
            <tr>{['warehouse', 'status', 'components', ''].map((h) => <th key={h} className="px-3 py-1.5 font-medium">{h}</th>)}</tr>
          </thead>
          <tbody>
            {list.map((w) => (
              <tr key={w.name} className="border-t border-slate-800/70 align-top">
                <td className="px-3 py-2 font-semibold text-slate-100">{w.name}</td>
                <td className="px-3 py-2">
                  <Badge tone={w.phase === 'ready' ? 'green' : w.phase.startsWith('failed') ? 'red' : 'blue'}>{w.phase}</Badge>
                </td>
                <td className="space-y-1 px-3 py-2">
                  <Component label="API" c={w.api} busy={busy} onState={(s) => run(() => manage('PUT', `/warehouses/${w.name}/api`, { state: s }))} />
                  <Component label="DB" c={w.db} busy={busy} onState={(s) => run(() => manage('PUT', `/warehouses/${w.name}/db`, { state: s }))} />
                </td>
                <td className="px-3 py-2 text-right">
                  <Button tone="danger" disabled={busy || w.phase === 'removing'} onClick={() => run(() => manage('DELETE', `/warehouses/${w.name}`))}>Remove</Button>
                </td>
              </tr>
            ))}
            {list.length === 0 && (
              <tr><td colSpan="4" className="px-3 py-4 text-center text-slate-500">no warehouse yet — create one above</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <p className="mt-2 text-[11px] text-slate-500">
        Stop/Freeze the API to see the health check and the circuit breaker; stop the database that is PRIMARY (see “Databases”) to see a new master being elected.
      </p>
    </Card>
  )
}
