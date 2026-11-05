import React from 'react'
import { Card, Badge, Stat } from './ui.jsx'

const statusTone = (i) => (i.status === 'DOWN' ? 'red' : i.breaker === 'OPEN' ? 'amber' : i.breaker === 'HALF_OPEN' ? 'blue' : 'green')

export default function ClusterPanel({ state, error }) {
  const instances = state?.instances ?? []
  const cache = state?.cache
  const queue = state?.queue
  return (
    <Card
      title="Cluster (service discovery)"
      right={error ? <Badge tone="red">gateway unreachable</Badge> : <Badge tone="green">gateway online</Badge>}
    >
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {instances.length === 0 && (
          <p className="col-span-full text-sm text-slate-400">No Data Warehouse instance has registered yet.</p>
        )}
        {instances.map((i) => (
          <div key={i.id} className="rounded-lg border border-slate-800 bg-slate-950/60 p-3">
            <div className="flex items-center justify-between">
              <span className="font-semibold text-slate-100">{i.id}</span>
              <Badge tone={statusTone(i)}>{i.status === 'DOWN' ? 'DOWN' : `UP · ${i.breaker}`}</Badge>
            </div>
            <div className="mono mt-1 text-xs text-slate-500">{i.url}</div>
            <div className="mt-2 flex gap-4 text-xs text-slate-400">
              <span>served <b className="text-slate-200">{i.served}</b></span>
              <span>failed <b className={i.failed ? 'text-rose-300' : 'text-slate-200'}>{i.failed}</b></span>
            </div>
            {i.lastFailure && <div className="mt-1 text-[11px] text-amber-300/80">last failure: {i.lastFailure}</div>}
          </div>
        ))}
      </div>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label="Redis cache"
          value={cache ? (cache.redisUp ? 'online' : 'offline') : '—'}
          sub={cache && `TTL ${cache.ttlSeconds}s`}
        />
        <Stat
          label="Hit ratio"
          value={cache ? `${Math.round(cache.hitRatio * 100)}%` : '—'}
          sub={cache && `${cache.hits} hits / ${cache.misses} misses`}
        />
        <Stat
          label="Upstream workers"
          value={queue ? `${queue.active}/${queue.workers}` : '—'}
          sub={queue && `${queue.queued} queued`}
        />
        <Stat
          label="Completed (H/N/L)"
          value={queue ? `${queue.completedByPriority.HIGH}/${queue.completedByPriority.NORMAL}/${queue.completedByPriority.LOW}` : '—'}
          sub="by priority"
        />
      </div>
    </Card>
  )
}
