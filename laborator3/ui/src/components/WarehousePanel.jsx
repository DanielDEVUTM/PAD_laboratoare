import React, { useCallback, useEffect, useState } from 'react'
import { Card, Badge, Button } from './ui.jsx'
import { manage, getJson } from '../api.js'

const tone = { running: 'green', stopped: 'red', frozen: 'amber', degraded: 'amber' }
const roleTone = { PRIMARY: 'green', SECONDARY: 'blue' }

/** One replica-set member: container state + its role in the election (PRIMARY / SECONDARY). */
function Member({ m, role, onState, busy }) {
  const down = m.state !== 'running'
  const label = down ? (m.state === 'frozen' ? 'FROZEN' : 'DOWN') : role ?? '…'
  const t = down ? 'red' : roleTone[role] ?? 'slate'
  return (
    <div className={`flex items-center justify-between rounded-md border px-2.5 py-1.5 ${role === 'PRIMARY' && !down ? 'border-emerald-600/50 bg-emerald-500/5' : 'border-slate-800 bg-slate-950/60'}`}>
      <div>
        <div className="mono text-[11px] text-slate-300">{m.container}</div>
        <Badge tone={t}>{label}</Badge>
      </div>
      <div className="flex gap-1">
        <Button disabled={busy || m.state === 'running'} onClick={() => onState('running')}>Start</Button>
        <Button disabled={busy || m.state === 'stopped'} onClick={() => onState('stopped')}>Stop</Button>
      </div>
    </div>
  )
}

/** Create / remove / stop warehouses (API + own 3-member database) at runtime. */
export default function WarehousePanel({ tick, sync }) {
  const [list, setList] = useState([])
  const [roles, setRoles] = useState({})
  const [name, setName] = useState('')
  const [count, setCount] = useState(1)
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      const l = await manage('GET', '/warehouses')
      setList(l)
      setError(null)
      const r = {}
      await Promise.all(l.filter((w) => w.api?.state === 'running' && w.phase === 'ready').map(async (w) => {
        try {
          const d = await getJson(`/admin/instances/${w.name}/db`)
          for (const m of d.members ?? []) r[m.name.split(':')[0]] = m.state
        } catch { /* API not registered yet */ }
      }))
      setRoles(r)
    } catch (e) { setError(String(e.message)) }
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

  const syncOf = (n) => sync?.find((s) => s.name === n)

  return (
    <Card
      title="Warehouses — each one = 1 API + its own database (replica set 1 + 2)"
      right={<Badge tone="slate">{list.length} warehouse{list.length === 1 ? '' : 's'}</Badge>}
    >
      <div className="mb-3 rounded-lg border border-slate-800 bg-slate-950/50 p-3 text-xs text-slate-400">
        <b className="text-slate-200">Structure “1 + 2”:</b> every warehouse has one <b className="text-slate-200">API</b> and one{' '}
        <b className="text-slate-200">database made of 3 MongoDB members</b>: 1 <Badge tone="green">PRIMARY</Badge> + 2{' '}
        <Badge tone="blue">SECONDARY</Badge>. If the primary dies, the other two elect a new master (try <i>Stop</i> on the PRIMARY).
        The <b className="text-slate-200">sync service</b> keeps the data identical in all warehouses.
      </div>

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
        <span className="text-[11px] text-slate-500">starts 3 databases + 1 API; data is copied from the existing warehouses before the API goes live</span>
      </form>

      {error && <p className="mb-2 text-xs text-rose-300">{error}</p>}

      <div className="grid gap-3 lg:grid-cols-2 xl:grid-cols-3">
        {list.map((w) => {
          const s = syncOf(w.name)
          const members = w.db?.members ?? []
          return (
            <div key={w.name} className="rounded-xl border border-slate-800 bg-slate-950/60 p-3">
              <div className="flex items-center justify-between">
                <span className="text-sm font-semibold text-slate-100">{w.name}</span>
                <div className="flex items-center gap-2">
                  <Badge tone={w.phase === 'ready' ? 'green' : w.phase.startsWith('failed') ? 'red' : 'blue'}>{w.phase}</Badge>
                  <Button tone="danger" disabled={busy || w.phase === 'removing'} onClick={() => run(() => manage('DELETE', `/warehouses/${w.name}`))}>Remove</Button>
                </div>
              </div>

              <div className="mt-3 text-[11px] uppercase tracking-wider text-slate-500">API</div>
              {w.api ? (
                <div className="mt-1 flex items-center gap-1.5">
                  <span className="mono text-[11px] text-slate-300">{w.api.container}</span>
                  <Badge tone={tone[w.api.state]}>{w.api.state}</Badge>
                  <Button disabled={busy || w.api.state === 'running'} onClick={() => run(() => manage('PUT', `/warehouses/${w.name}/api`, { state: 'running' }))}>Start</Button>
                  <Button disabled={busy || w.api.state === 'stopped'} onClick={() => run(() => manage('PUT', `/warehouses/${w.name}/api`, { state: 'stopped' }))}>Stop</Button>
                  <Button disabled={busy || w.api.state !== 'running'} onClick={() => run(() => manage('PUT', `/warehouses/${w.name}/api`, { state: 'frozen' }))}>Freeze</Button>
                </div>
              ) : <div className="mt-1 text-xs text-slate-600">not started yet</div>}

              <div className="mt-3 flex items-center justify-between text-[11px] uppercase tracking-wider text-slate-500">
                <span>Database · replica set rs-{w.name}</span>
                {s && (
                  <Badge tone={s.state === 'READY' && s.pendingChanges === 0 ? 'green' : 'amber'}>
                    sync {s.state === 'READY' ? (s.pendingChanges ? `${s.pendingChanges} pending` : 'in sync') : s.state.toLowerCase()}
                  </Badge>
                )}
              </div>
              <div className="mt-1 space-y-1.5">
                {members.length === 0 && <div className="text-xs text-slate-600">starting…</div>}
                {members.map((m, i) => (
                  <Member key={m.container} m={m} role={roles[m.container]} busy={busy}
                    onState={(st) => run(() => manage('PUT', `/warehouses/${w.name}/db/${i + 1}`, { state: st }))} />
                ))}
              </div>
            </div>
          )
        })}
        {list.length === 0 && <p className="col-span-full py-4 text-center text-sm text-slate-500">no warehouse yet — create one above</p>}
      </div>
    </Card>
  )
}
