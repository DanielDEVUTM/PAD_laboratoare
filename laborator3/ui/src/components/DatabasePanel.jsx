import React, { useEffect, useState } from 'react'
import { Card, Badge } from './ui.jsx'
import { getJson } from '../api.js'

/** What the databases really contain, read from each node DIRECTLY (bypassing balancing and cache). */
export default function DatabasePanel({ instances, tick }) {
  const [selected, setSelected] = useState(null)
  const [rows, setRows] = useState({})
  const [dbs, setDbs] = useState({})

  const up = instances.filter((i) => i.status === 'UP')
  const active = selected && instances.find((i) => i.id === selected) ? selected : instances[0]?.id

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      const result = {}
      await Promise.all(
        instances.map(async (i) => {
          try {
            result[i.id] = await getJson(`/admin/instances/${i.id}/employees?limit=200`)
          } catch {
            result[i.id] = null
          }
        }),
      )
      const dbInfo = {}
      await Promise.all(up.map(async (i) => {
        try { dbInfo[i.id] = await getJson(`/admin/instances/${i.id}/db`) } catch { /* unreachable */ }
      }))
      if (!cancelled) { setRows(result); setDbs(dbInfo) }
    })()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, instances.map((i) => i.id + i.status).join()])

  const counts = instances.map((i) => rows[i.id]?.total)
  const inSync = counts.length > 0 && counts.every((c) => c !== undefined && c === counts[0])
  const page = rows[active]
  const db = dbs[active]

  const roleTone = (s) => (s === 'PRIMARY' ? 'green' : s === 'SECONDARY' ? 'blue' : 'red')

  return (
    <Card
      title="Databases — what each warehouse really contains"
      right={
        instances.length > 0 &&
        (inSync ? <Badge tone="green">all nodes in sync · {counts[0]} docs</Badge> : <Badge tone="amber">nodes differ {counts.join(' / ')}</Badge>)
      }
    >
      <div className="mb-3 flex flex-wrap gap-2">
        {db?.members?.map((m) => (
          <div key={m.name} className="rounded-lg border border-slate-800 bg-slate-950/60 px-3 py-1.5 text-xs">
            <span className="mono text-slate-200">{m.name}</span>{' '}
            <Badge tone={roleTone(m.state)}>{m.state.startsWith('(') ? 'DOWN' : m.state}</Badge>
          </div>
        ))}
        {db?.replicaSet && <span className="self-center text-xs text-slate-500">replica set <b className="text-slate-300">{db.replicaSet}</b> of warehouse {active}</span>}
        {!db?.members && <span className="text-xs text-slate-500">replica-set status unavailable</span>}
      </div>

      <div className="mb-2 flex gap-1 border-b border-slate-800">
        {instances.map((i) => (
          <button
            key={i.id}
            onClick={() => setSelected(i.id)}
            className={`-mb-px border-b-2 px-3 py-1.5 text-xs ${active === i.id ? 'border-sky-500 text-sky-300' : 'border-transparent text-slate-400 hover:text-slate-200'}`}
          >
            {i.id}
          </button>
        ))}
      </div>

      <p className="mb-2 text-xs text-slate-500">
        Documents as seen by <b className="text-slate-300">{active ?? '—'}</b> (direct read of its database connection)
      </p>
      <div className="max-h-72 overflow-auto rounded-lg border border-slate-800">
        <table className="w-full text-left text-xs">
          <thead className="sticky top-0 bg-slate-900 text-slate-400">
            <tr>
              {['id', 'name', 'email', 'department', 'salary'].map((h) => (
                <th key={h} className="px-3 py-1.5 font-medium">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {page?.items?.map((e) => (
              <tr key={e.id} className="border-t border-slate-800/70">
                <td className="mono px-3 py-1 text-slate-400">{e.id.length > 8 ? e.id.slice(0, 8) + '…' : e.id}</td>
                <td className="px-3 py-1">{e.firstName} {e.lastName}</td>
                <td className="px-3 py-1 text-slate-400">{e.email}</td>
                <td className="px-3 py-1">{e.department}</td>
                <td className="mono px-3 py-1">{e.salary}</td>
              </tr>
            ))}
            {!page && (
              <tr><td colSpan="5" className="px-3 py-4 text-center text-slate-500">no data (instance unreachable)</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </Card>
  )
}
