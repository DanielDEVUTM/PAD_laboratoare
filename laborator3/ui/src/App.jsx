import React, { useCallback, useEffect, useState } from 'react'
import { GATEWAY, getJson } from './api.js'
import ClusterPanel from './components/ClusterPanel.jsx'
import DatabasePanel from './components/DatabasePanel.jsx'
import EmployeesPanel from './components/EmployeesPanel.jsx'
import ResponsePanel from './components/ResponsePanel.jsx'
import PriorityDemo from './components/PriorityDemo.jsx'
import EventLog from './components/EventLog.jsx'
import WarehousePanel from './components/WarehousePanel.jsx'

export default function App() {
  const [state, setState] = useState(null)
  const [events, setEvents] = useState([])
  const [sync, setSync] = useState([])
  const [error, setError] = useState(false)
  const [response, setResponse] = useState(null)
  const [tick, setTick] = useState(0)

  const refresh = useCallback(async () => {
    try {
      const [s, e] = await Promise.all([getJson('/admin/state'), getJson('/admin/events')])
      setState(s); setEvents(e); setError(false)
      try { setSync(await getJson('/sync/warehouses')) } catch { setSync([]) }
    } catch {
      setError(true)
    }
  }, [])

  useEffect(() => {
    refresh()
    const t = setInterval(() => { refresh(); setTick((x) => x + 1) }, 2500)
    return () => clearInterval(t)
  }, [refresh])

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      <header className="mb-6 flex flex-wrap items-end justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold text-slate-50">Data Warehouse Console</h1>
          <p className="text-sm text-slate-400">
            Lab 3 · web proxy · all traffic goes through <span className="mono text-sky-300">{GATEWAY}</span>
          </p>
        </div>
        <span className="text-xs text-slate-500">auto-refresh 2.5 s</span>
      </header>

      <div className="grid gap-4">
        <WarehousePanel tick={tick} sync={sync} />
        <ClusterPanel state={state} error={error} />
        <div className="grid gap-4 lg:grid-cols-2">
          <DatabasePanel instances={state?.instances ?? []} tick={tick} />
          <EmployeesPanel onResponse={setResponse} onChanged={refresh} tick={tick} />
        </div>
        <div className="grid gap-4 lg:grid-cols-3">
          <ResponsePanel response={response} />
          <PriorityDemo />
          <EventLog events={events} />
        </div>
      </div>
    </div>
  )
}
