import React from 'react'
import { Card, Badge } from './ui.jsx'

const tone = { REGISTRY: 'blue', HEALTH: 'amber', BREAKER: 'red', RETRY: 'amber', CACHE: 'green' }

export default function EventLog({ events }) {
  return (
    <Card title="Gateway events">
      <ul className="max-h-72 space-y-1 overflow-auto text-xs">
        {events.length === 0 && <li className="text-slate-500">no events yet</li>}
        {events.map((e, idx) => (
          <li key={idx} className="flex items-start gap-2">
            <span className="mono shrink-0 text-slate-500">{e.time.slice(11, 19)}</span>
            <Badge tone={tone[e.type] ?? 'slate'}>{e.type}</Badge>
            <span className="text-slate-300">{e.message}</span>
          </li>
        ))}
      </ul>
    </Card>
  )
}
