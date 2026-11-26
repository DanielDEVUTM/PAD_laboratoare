import React from 'react'

export function Card({ title, right, children, className = '' }) {
  return (
    <section className={`rounded-xl border border-slate-800 bg-slate-900/60 ${className}`}>
      <header className="flex items-center justify-between border-b border-slate-800 px-4 py-2.5">
        <h2 className="text-sm font-semibold tracking-wide text-slate-100">{title}</h2>
        <div className="flex items-center gap-2 text-xs text-slate-400">{right}</div>
      </header>
      <div className="p-4">{children}</div>
    </section>
  )
}

const tones = {
  green: 'bg-emerald-500/15 text-emerald-300 ring-emerald-500/30',
  red: 'bg-rose-500/15 text-rose-300 ring-rose-500/30',
  amber: 'bg-amber-500/15 text-amber-300 ring-amber-500/30',
  blue: 'bg-sky-500/15 text-sky-300 ring-sky-500/30',
  slate: 'bg-slate-500/15 text-slate-300 ring-slate-500/30',
}

export function Badge({ tone = 'slate', children }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset ${tones[tone]}`}>
      {children}
    </span>
  )
}

export function Button({ children, tone = 'default', className = '', ...props }) {
  const styles = {
    default: 'bg-slate-800 hover:bg-slate-700 text-slate-100',
    primary: 'bg-sky-600 hover:bg-sky-500 text-white',
    danger: 'bg-rose-700 hover:bg-rose-600 text-white',
  }
  return (
    <button
      {...props}
      className={`rounded-md px-3 py-1.5 text-xs font-medium transition disabled:opacity-40 ${styles[tone]} ${className}`}
    >
      {children}
    </button>
  )
}

export function Select({ value, onChange, options, label }) {
  return (
    <label className="flex items-center gap-1.5 text-xs text-slate-400">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="rounded-md border border-slate-700 bg-slate-900 px-2 py-1 text-slate-100"
      >
        {options.map((o) => (
          <option key={o} value={o}>{o}</option>
        ))}
      </select>
    </label>
  )
}

export function Stat({ label, value, sub }) {
  return (
    <div className="rounded-lg bg-slate-800/50 px-3 py-2">
      <div className="text-[11px] uppercase tracking-wider text-slate-400">{label}</div>
      <div className="mono text-lg text-slate-50">{value}</div>
      {sub && <div className="text-[11px] text-slate-500">{sub}</div>}
    </div>
  )
}
