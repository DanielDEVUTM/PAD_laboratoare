import React, { useCallback, useEffect, useState } from 'react'
import { Card, Button, Select, Badge } from './ui.jsx'
import { call } from '../api.js'

const empty = { id: '', firstName: '', lastName: '', email: '', department: '', position: '', salary: '', hireDate: '' }
const PAGE = 8

/** CRUD through the gateway. 'format' decides JSON or XML for BOTH the request body and the response. */
export default function EmployeesPanel({ onResponse, onChanged, tick }) {
  const [format, setFormat] = useState('json')
  const [priority, setPriority] = useState('normal')
  const [offset, setOffset] = useState(0)
  const [page, setPage] = useState({ items: [], total: 0 })
  const [form, setForm] = useState(empty)
  const [editing, setEditing] = useState(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async (record = false) => {
    try {
      // the table is always read as JSON; the explicit format is used by the actions below
      const r = await call('GET', `/employees?offset=${offset}&limit=${PAGE}`, { priority })
      if (r.ok) setPage(JSON.parse(r.text))
      if (record) onResponse(r)
    } catch (e) {
      onResponse({ status: 0, ok: false, text: String(e), method: 'GET', url: '/employees', meta: {}, ms: 0 })
    }
  }, [offset, priority, onResponse])

  useEffect(() => { load() }, [load, tick])

  const act = async (method, path, body) => {
    setBusy(true)
    try {
      const r = await call(method, path, { format, priority, body })
      onResponse(r)
      onChanged()
      await load()
      return r
    } catch (e) {
      onResponse({ status: 0, ok: false, text: String(e), method, url: path, meta: {}, ms: 0 })
    } finally {
      setBusy(false)
    }
  }

  const submit = async (e) => {
    e.preventDefault()
    const { id, ...rest } = form
    const body = Object.fromEntries(Object.entries(rest).filter(([, v]) => v !== ''))
    if (body.salary) body.salary = Number(body.salary)
    const r = editing
      ? await act('PUT', `/employees/${editing}`, body)
      : id
        ? await act('PUT', `/employees/${id}`, body)
        : await act('POST', '/employees', body)
    if (r?.ok) { setForm(empty); setEditing(null) }
  }

  const edit = (e) => {
    setEditing(e.id)
    setForm({ ...empty, ...Object.fromEntries(Object.entries(e).map(([k, v]) => [k, v ?? ''])) })
  }

  const input = (k, label, type = 'text', req = false) => (
    <label className="flex flex-col gap-0.5 text-[11px] text-slate-400">
      {label}
      <input
        type={type}
        required={req}
        disabled={k === 'id' && !!editing}
        value={form[k]}
        onChange={(e) => setForm({ ...form, [k]: e.target.value })}
        className="rounded-md border border-slate-700 bg-slate-950 px-2 py-1 text-xs text-slate-100 disabled:opacity-50"
      />
    </label>
  )

  const pages = Math.max(1, Math.ceil(page.total / PAGE))

  return (
    <Card
      title="Employees (through the gateway)"
      right={
        <>
          <Select label="format" value={format} onChange={setFormat} options={['json', 'xml']} />
          <Select label="priority" value={priority} onChange={setPriority} options={['low', 'normal', 'high']} />
        </>
      }
    >
      <div className="overflow-auto rounded-lg border border-slate-800">
        <table className="w-full text-left text-xs">
          <thead className="bg-slate-900 text-slate-400">
            <tr>{['id', 'name', 'department', 'salary', ''].map((h) => <th key={h} className="px-3 py-1.5 font-medium">{h}</th>)}</tr>
          </thead>
          <tbody>
            {page.items?.map((e) => (
              <tr key={e.id} className="border-t border-slate-800/70">
                <td className="mono px-3 py-1 text-slate-400">{e.id.length > 8 ? e.id.slice(0, 8) + '…' : e.id}</td>
                <td className="px-3 py-1">{e.firstName} {e.lastName}</td>
                <td className="px-3 py-1">{e.department}</td>
                <td className="mono px-3 py-1">{e.salary}</td>
                <td className="whitespace-nowrap px-3 py-1 text-right">
                  <Button onClick={() => act('GET', `/employees/${e.id}`)} className="mr-1">GET</Button>
                  <Button onClick={() => edit(e)} className="mr-1">Edit</Button>
                  <Button tone="danger" disabled={busy} onClick={() => act('DELETE', `/employees/${e.id}`)}>Delete</Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-2 flex items-center justify-between text-xs text-slate-400">
        <span>offset={offset} · limit={PAGE} · total={page.total}</span>
        <span className="flex items-center gap-2">
          <Button disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>‹ Prev</Button>
          {Math.floor(offset / PAGE) + 1}/{pages}
          <Button disabled={offset + PAGE >= page.total} onClick={() => setOffset(offset + PAGE)}>Next ›</Button>
          <Button onClick={() => load(true)}>GET list</Button>
        </span>
      </div>

      <form onSubmit={submit} className="mt-4 rounded-lg border border-slate-800 bg-slate-950/50 p-3">
        <div className="mb-2 flex items-center gap-2 text-xs">
          {editing ? <Badge tone="amber">PUT /employees/{editing}</Badge> : <Badge tone="blue">POST /employees (or PUT when id is set)</Badge>}
        </div>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {input('id', 'id (optional)')}
          {input('firstName', 'firstName', 'text', true)}
          {input('lastName', 'lastName', 'text', true)}
          {input('email', 'email', 'email', true)}
          {input('department', 'department')}
          {input('position', 'position')}
          {input('salary', 'salary', 'number')}
          {input('hireDate', 'hireDate', 'date')}
        </div>
        <div className="mt-3 flex gap-2">
          <Button tone="primary" type="submit" disabled={busy}>{editing ? 'Save (PUT)' : 'Create'}</Button>
          {editing && <Button type="button" onClick={() => { setEditing(null); setForm(empty) }}>Cancel</Button>}
        </div>
      </form>
    </Card>
  )
}
