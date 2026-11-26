import { describe, expect, it } from 'vitest'
import { buildUrl, toXml } from './api.js'

describe('buildUrl', () => {
  it('uses a full gateway address as it is (local run)', () => {
    expect(buildUrl('/employees', 'json', 'http://localhost:8000', 'http://localhost:3000').href)
      .toBe('http://localhost:8000/employees')
  })

  it('resolves a relative gateway path on the origin of the page (behind the reverse proxy)', () => {
    expect(buildUrl('/employees?limit=1', 'xml', '/api', 'https://demo.example.com').href)
      .toBe('https://demo.example.com/api/employees?limit=1&format=xml')
  })
})

describe('toXml', () => {
  it('escapes markup and drops empty fields', () => {
    expect(toXml({ firstName: 'A<B>&', lastName: '', salary: 0, department: null }))
      .toBe('<employee><firstName>A&lt;B&gt;&amp;</firstName><salary>0</salary></employee>')
  })
})
